package im.actor

import bintray._
import com.typesafe.sbt.packager.debian.DebianPlugin.autoImport._
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport._
import com.typesafe.sbt.pgp.PgpKeys
import ohnosequences.sbt.SbtGithubReleasePlugin.autoImport._
import sbt.Keys._
import sbt._
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._

trait Releasing {
  private val publishDeb = taskKey[Unit]("Publish to debian repository")

  private val taskSetting = publishDeb := {
    val btyOrg = "actor"
    val repoName = "ubuntu"
    val pkgName = "actor"
    val vers = version.value
    val f = (packageBin in Debian).value
    val path = "pool/main/a/" + name.value + "/" + f.getName + ";deb_distribution=trusty;deb_component=main;deb_architecture=all"
    val log = streams.value.log

    val creds = getCreds

    val repo = BintrayRepo(creds, Some(btyOrg), repoName)
    repo.upload(pkgName, vers, path, f, log)
    repo.release(pkgName, vers, log)
  }

  val releaseSettings = Seq(
    taskSetting,
    releaseCommitMessage := s"chore(server): setting version to ${(version in ThisBuild).value}",
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      setReleaseVersion,
      commitReleaseVersion,
      ReleaseStep(
        action = { state =>
          val extracted = Project extract state

          val (s, distZip) = extracted runTask (dist in Universal in extracted.get(thisProjectRef), state)

          val notesDir = new File("/tmp/notes")

          val emptyFile = new File("/tmp/empty")
          emptyFile.createNewFile()

          val newState = extracted.append(Seq(
            GithubRelease.repo := "actor-platform/actor-bootstrap",
            GithubRelease.releaseName := "actor-server",
            GithubRelease.draft := false,
            GithubRelease.tag := s"v${extracted.get(version)}",
            GithubRelease.releaseAssets := Seq(distZip),
            GithubRelease.notesFile := emptyFile
          ), s)
          extracted runTask(checkGithubCredentials, newState)
          (extracted runTask(releaseOnGithub in extracted.get(thisProjectRef), newState))._1
        }
      ),
      ReleaseStep(
        action = { state =>
          val extracted = Project extract state
          extracted runAggregated (PgpKeys.publishSigned in Global in extracted.get(thisProjectRef), state)
        },
        enableCrossBuild = true
      ),
      ReleaseStep(
        action = { state =>
          val extracted = Project extract state
          (extracted runTask (publishDeb in Global in extracted.get(thisProjectRef), state))._1
        }
      ),
      ReleaseStep(action = Command.process("sonatypeReleaseAll", _)),
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  )

  private def getCreds: BintrayCredentials = {
    (for {
      user <- sys.env.get("BINTRAY_USER")
      key <- sys.env.get("BINTRAY_API_KEY")
    } yield BintrayCredentials(user, key)) getOrElse {
      throw new RuntimeException("BINTRAY_USER or BINTRAY_API_KEY is not defined")
    }
  }
}