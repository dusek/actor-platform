package im.actor.api.rpc

import cats.data.Xor
import im.actor.server.acl.ACLUtils
import im.actor.server.db.DbExtension
import im.actor.server.model._
import im.actor.server.persist._

import scala.concurrent.{ Future, ExecutionContext }

import akka.actor._
import slick.dbio.DBIO

import im.actor.api.rpc.peers._
import im.actor.server.api.rpc.service.groups.GroupRpcErrors
import im.actor.util.misc.StringUtils

object PeerHelpers {
  def withOutPeerF[R <: RpcResponse](
    outPeer: ApiOutPeer
  )(
    f: ⇒ Future[RpcError Xor R]
  )(implicit client: AuthorizedClientData, actorSystem: ActorSystem, ec: ExecutionContext): Future[RpcError Xor R] =
    DbExtension(actorSystem).db.run(withOutPeer(outPeer)(DBIO.from(f)))

  def withOutPeer[R <: RpcResponse](
    outPeer: ApiOutPeer
  )(
    f: ⇒ DBIO[RpcError Xor R]
  )(implicit client: AuthorizedClientData, actorSystem: ActorSystem, ec: ExecutionContext): DBIO[RpcError Xor R] = {
    outPeer.`type` match {
      case ApiPeerType.Private ⇒
        DBIO.from(ACLUtils.checkOutPeer(outPeer, client.authId)) flatMap {
          case false ⇒ DBIO.successful(Error(CommonRpcErrors.InvalidAccessHash))
          case true  ⇒ f
        }
      case ApiPeerType.Group ⇒
        (for {
          optGroup ← GroupRepo.find(outPeer.id)
          grouperrOrGroup ← validGroup(optGroup)
          hasherrOrGroup ← DBIO.successful(grouperrOrGroup.map(validGroupAccessHash(outPeer.accessHash, _)))
        } yield hasherrOrGroup).flatMap {
          case Error(err) ⇒ DBIO.successful(Error(err))
          case _          ⇒ f
        }
    }
  }

  def withOutPeerAsGroupPeer[R <: RpcResponse](outPeer: ApiOutPeer)(
    f: ApiGroupOutPeer ⇒ DBIO[RpcError Xor R]
  )(implicit client: AuthorizedClientData, actorSystem: ActorSystem, ec: ExecutionContext): DBIO[RpcError Xor R] = {
    outPeer.`type` match {
      case ApiPeerType.Group   ⇒ f(ApiGroupOutPeer(outPeer.id, outPeer.accessHash))
      case ApiPeerType.Private ⇒ DBIO.successful(Error(RpcError(403, "PEER_IS_NOT_GROUP", "", false, None)))
    }
  }

  def withUserOutPeerF[R <: RpcResponse](userOutPeer: ApiUserOutPeer)(f: ⇒ Future[RpcError Xor R])(
    implicit
    client:      AuthorizedClientData,
    actorSystem: ActorSystem,
    ec:          ExecutionContext
  ): Future[RpcError Xor R] =
    DbExtension(actorSystem).db.run(withUserOutPeer(userOutPeer)(DBIO.from(f)))

  def withUserOutPeer[R <: RpcResponse](userOutPeer: ApiUserOutPeer)(f: ⇒ DBIO[RpcError Xor R])(
    implicit
    client:      AuthorizedClientData,
    actorSystem: ActorSystem,
    ec:          ExecutionContext
  ): DBIO[RpcError Xor R] = {
    renderCheckResult(Seq(checkUserPeer(userOutPeer.userId, userOutPeer.accessHash)), f)
  }

  def withOwnGroupMember[R <: RpcResponse](groupOutPeer: ApiGroupOutPeer, userId: Int)(f: FullGroup ⇒ DBIO[RpcError Xor R])(implicit ec: ExecutionContext): DBIO[RpcError Xor R] = {
    withGroupOutPeer(groupOutPeer) { group ⇒
      (for (user ← GroupUserRepo.find(group.id, userId)) yield user).flatMap {
        case Some(user) ⇒ f(group)
        case None       ⇒ DBIO.successful(Error(CommonRpcErrors.forbidden("You are not a group member.")))
      }
    }
  }

  def withGroupAdmin[R <: RpcResponse](groupOutPeer: ApiGroupOutPeer)(f: FullGroup ⇒ DBIO[RpcError Xor R])(implicit client: AuthorizedClientData, ec: ExecutionContext): DBIO[RpcError Xor R] = {
    withOwnGroupMember(groupOutPeer, client.userId) { group ⇒
      (for (user ← GroupUserRepo.find(group.id, client.userId)) yield user).flatMap {
        case Some(gu) if gu.isAdmin ⇒ f(group)
        case _                      ⇒ DBIO.successful(Error(CommonRpcErrors.forbidden("Only admin can perform this action.")))
      }
    }
  }

  def withValidGroupTitle[R <: RpcResponse](title: String)(f: String ⇒ DBIO[RpcError Xor R])(
    implicit
    client:      AuthorizedClientData,
    actorSystem: ActorSystem,
    ec:          ExecutionContext
  ): DBIO[RpcError Xor R] = StringUtils.validName(title) match {
    case Xor.Left(err)         ⇒ DBIO.successful(Error(GroupRpcErrors.WrongGroupTitle))
    case Xor.Right(validTitle) ⇒ f(validTitle)
  }

  def withUserOutPeers[R <: RpcResponse](userOutPeers: Seq[ApiUserOutPeer])(f: ⇒ DBIO[RpcError Xor R])(
    implicit
    client:      AuthorizedClientData,
    actorSystem: ActorSystem,
    ec:          ExecutionContext
  ): DBIO[RpcError Xor R] = {
    val checkOptsFutures = userOutPeers map {
      case ApiUserOutPeer(userId, accessHash) ⇒
        checkUserPeer(userId, accessHash)
    }

    renderCheckResult(checkOptsFutures, f)
  }

  val InvalidToken = RpcError(403, "INVALID_INVITE_TOKEN", "No correct token provided.", false, None)

  def withValidInviteToken[R <: RpcResponse](baseUrl: String, urlOrToken: String)(f: (FullGroup, GroupInviteToken) ⇒ DBIO[RpcError Xor R])(
    implicit
    client:      AuthorizedClientData,
    actorSystem: ActorSystem,
    ec:          ExecutionContext
  ): DBIO[RpcError Xor R] = {
    val extractedToken =
      if (urlOrToken.startsWith(baseUrl)) {
        urlOrToken.drop(genInviteUrl(baseUrl).length).takeWhile(c ⇒ c != '?' && c != '#')
      } else {
        urlOrToken
      }

    extractedToken.isEmpty match {
      case false ⇒ (for {
        token ← GroupInviteTokenRepo.findByToken(extractedToken)
        group ← token.map(gt ⇒ GroupRepo.findFull(gt.groupId)).getOrElse(DBIO.successful(None))
      } yield for (g ← group; t ← token) yield (g, t)).flatMap {
        case Some((g, t)) ⇒ f(g, t)
        case None         ⇒ DBIO.successful(Error(InvalidToken))
      }
      case true ⇒ DBIO.successful(Error(InvalidToken))
    }
  }

  def withKickableGroupMember[R <: RpcResponse](
    groupOutPeer:    ApiGroupOutPeer,
    kickUserOutPeer: ApiUserOutPeer
  )(f: FullGroup ⇒ DBIO[RpcError Xor R])(
    implicit
    client:      AuthorizedClientData,
    actorSystem: ActorSystem,
    ec:          ExecutionContext
  ): DBIO[RpcError Xor R] = {
    withGroupOutPeer(groupOutPeer) { group ⇒
      GroupUserRepo.find(group.id, kickUserOutPeer.userId).flatMap {
        case Some(GroupUser(_, _, inviterUserId, _, _, _)) ⇒
          if (kickUserOutPeer.userId != client.userId && (inviterUserId == client.userId || group.creatorUserId == client.userId)) {
            f(group)
          } else {
            DBIO.successful(Error(CommonRpcErrors.forbidden("You are permitted to kick this user.")))
          }
        case None ⇒ DBIO.successful(Error(RpcError(404, "USER_NOT_FOUND", "User is not a group member.", false, None)))
      }
    }
  }

  def withPublicGroup[R <: RpcResponse](groupOutPeer: ApiGroupOutPeer)(f: FullGroup ⇒ DBIO[RpcError Xor R])(
    implicit
    client:      AuthorizedClientData,
    actorSystem: ActorSystem,
    ec:          ExecutionContext
  ): DBIO[RpcError Xor R] = {
    withGroupOutPeer(groupOutPeer) { group ⇒
      if (group.isPublic) {
        f(group)
      } else {
        DBIO.successful(Error(RpcError(400, "GROUP_IS_NOT_PUBLIC", "The group is not public.", false, None)))
      }
    }
  }

  def genInviteUrl(baseUrl: String, token: String = "") = s"$baseUrl/join/$token"

  private def checkUserPeer(userId: Int, accessHash: Long)(
    implicit
    client:      AuthorizedClientData,
    actorSystem: ActorSystem,
    ec:          ExecutionContext
  ): DBIO[Option[Boolean]] = {
    for {
      userOpt ← UserRepo.find(userId)
    } yield {
      userOpt map (u ⇒ ACLUtils.userAccessHash(client.authId, u.id, u.accessSalt) == accessHash)
    }
  }

  private def withGroupOutPeer[R <: RpcResponse](groupOutPeer: ApiGroupOutPeer)(f: FullGroup ⇒ DBIO[RpcError Xor R])(implicit ec: ExecutionContext): DBIO[RpcError Xor R] = {
    GroupRepo.findFull(groupOutPeer.groupId) flatMap {
      case Some(group) ⇒
        if (group.accessHash != groupOutPeer.accessHash) {
          DBIO.successful(Error(CommonRpcErrors.InvalidAccessHash))
        } else {
          f(group)
        }
      case None ⇒
        DBIO.successful(Error(CommonRpcErrors.GroupNotFound))
    }
  }

  private def validGroup(optGroup: Option[Group]) = {
    optGroup match {
      case Some(group) ⇒
        DBIO.successful(Xor.right(group))
      case None ⇒ DBIO.successful(Error(CommonRpcErrors.GroupNotFound))
    }
  }

  private def validGroupAccessHash(accessHash: Long, group: Group)(implicit client: BaseClientData, actorSystem: ActorSystem) = {
    if (accessHash == group.accessHash) {
      Xor.right(group)
    } else {
      Error(CommonRpcErrors.InvalidAccessHash)
    }
  }

  private def renderCheckResult[R <: RpcResponse](checkOptsActions: Seq[DBIO[Option[Boolean]]], f: ⇒ DBIO[RpcError Xor R])(implicit ec: ExecutionContext): DBIO[RpcError Xor R] = {
    DBIO.sequence(checkOptsActions) flatMap { checkOpts ⇒
      if (checkOpts.contains(None)) {
        DBIO.successful(Error(RpcError(404, "PEER_NOT_FOUND", "Peer not found.", false, None)))
      } else if (checkOpts.flatten.contains(false)) {
        DBIO.successful(Error(RpcError(401, "ACCESS_HASH_INVALID", "Invalid access hash.", false, None)))
      } else {
        f
      }
    }
  }
}
