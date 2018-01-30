/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package utils

import java.net.URL
import java.util.concurrent.TimeoutException

import org.apache.commons.codec.binary.Base64
import play.api.Configuration
import play.api.http.{HeaderNames, HttpVerbs, MimeTypes, Status}
import play.api.libs.json.Reads._
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import play.api.routing.sird.QueryStringParameterExtractor
import play.api.libs.concurrent.Futures

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.util.Try
import GitHub._

import scala.concurrent.duration.FiniteDuration

class GitHub(configuration: Configuration, ws: WSClient, futures: Futures)(implicit ec: ExecutionContext) {

  lazy val clientId: String = configuration.get[String]("github.oauth.client-id")
  lazy val clientSecret: String = configuration.get[String]("github.oauth.client-secret")

  def ws(path: String, accessToken: String): WSRequest = {
    ws
      .url(s"https://api.github.com/$path")
      .withHttpHeaders(
        HeaderNames.AUTHORIZATION -> s"token $accessToken",
        HeaderNames.ACCEPT -> "application/vnd.github.drax-preview+json"
      )
  }

  def accessToken(code: String): Future[String] = {
    val queryStringParameters = Seq(
      "client_id" -> clientId,
      "client_secret" -> clientSecret,
      "code" -> code
    )

    val wsFuture = ws
      .url("https://github.com/login/oauth/access_token")
      .withQueryStringParameters(queryStringParameters:_*)
      .withHttpHeaders(HeaderNames.ACCEPT -> MimeTypes.JSON)
      .withMethod(HttpVerbs.POST)
      .execute()

    wsFuture.flatMap { response =>
      (response.json \ "access_token").asOpt[String].fold {
        val maybeError = (response.json \ "error_description").asOpt[String]
        Future.failed[String](new Exception(maybeError.getOrElse(response.body)))
      } {
        Future.successful
      }
    }
  }

  private def fetchPages(path: String, accessToken: String, pageSize: Int = 100): Future[JsArray] = {

    implicit class Regex(sc: StringContext) {
      def r = new scala.util.matching.Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
    }

    def req(path: String, accessToken: String, page: Int, pageSize: Int): Future[WSResponse] = {
      ws(path, accessToken).withQueryStringParameters("page" -> page.toString, "per_page" -> pageSize.toString).get()
    }

    // get the first page
    req(path, accessToken, 1, pageSize).flatMap { response =>
      val firstPageRepos = response.json.as[JsArray]

      def urlToPage(urlString: String): Option[Int] = {
        QueryStringParameterExtractor.required("page").unapply(new URL(urlString)).map(_.toInt)
      }

      val pages = response.header("Link") match {
        case Some(r"""<(.*)$n>; rel="next", <(.*)$l>; rel="last"""") =>
          (urlToPage(n), urlToPage(l)) match {
            case (Some(nextNum), Some(lastNum)) => Range(nextNum, lastNum).inclusive
            case _ => Range(0, 0)
          }
        case _ =>
          Range(0, 0)
      }

      val pagesFutures = pages.map(req(path, accessToken, _, pageSize).map(_.json.as[JsArray]))

      // assume numeric paging so we can parallelize
      Future.foldLeft(pagesFutures)(firstPageRepos)(_ ++ _)
    }
  }

  def user(accessToken: String): Future[JsObject] = {
    ws("user", accessToken).get().flatMap(okT[JsObject])
  }

  def orgOrUser(orgOrUser: String, accessToken: String): Future[JsObject] = {
    ws(s"orgs/$orgOrUser", accessToken).get().flatMap(okT[JsObject]).recoverWith {
      case _: Exception => ws(s"users/$orgOrUser", accessToken).get().flatMap(okT[JsObject])
    }
  }

  def orgOrUserRepos(orgOrUser: String, accessToken: String, pageSize: Int = 100): Future[JsArray] = {
    fetchPages(s"orgs/$orgOrUser/repos", accessToken, pageSize).recoverWith {
      case _: Exception => fetchPages(s"users/$orgOrUser/repos", accessToken, pageSize)
    }
  }

  def repo(ownerRepo: OwnerRepoBase, accessToken: String): Future[JsObject] = {
    ws(s"repos/${ownerRepo.ownerRepo}", accessToken).get().flatMap(okT[JsObject])
  }

  def licenses(accessToken: String): Future[JsArray] = {
    ws("licenses", accessToken).get().flatMap(okT[JsArray])
  }

  def license(key: String, accessToken: String): Future[JsObject] = {
    ws(s"licenses/$key", accessToken).get().flatMap(okT[JsObject])
  }

  def licenseParams(key: String, accessToken: String): Future[Set[String]] = {
    license(key, accessToken).map { licenseInfo =>
      val body = (licenseInfo \ "body").as[String]
      val paramMatcher = "(?<=\\[).+?(?=\\])".r
      paramMatcher.findAllIn(body).toSet
    }
  }

  def repoLicense(ownerRepo: OwnerRepoBase, accessToken: String): Future[Option[String]] = {
    repo(ownerRepo, accessToken).map { repoInfo =>
      (repoInfo \ "license" \ "name").asOpt[String]
    }
  }

  def getCommits(ownerRepo: OwnerRepoBase, accessToken: String): Future[JsArray] = {
    ws(s"repos/${ownerRepo.owner}/${ownerRepo.repo}/commits", accessToken).get().flatMap(okT[JsArray])
  }

  def getAllGitRefs(ownerRepo: OwnerRepoBase, accessToken: String): Future[JsArray] = {
    ws(s"repos/${ownerRepo.owner}/${ownerRepo.repo}/git/refs", accessToken).get().flatMap(okT[JsArray])
  }

  def getFile(ownerRepo: OwnerRepoBase, path: String, accessToken: String): Future[JsObject] = {
    val baseWs = ws(s"repos/${ownerRepo.ownerRepo}/contents/$path", accessToken)

    val wsMaybeBranch = ownerRepo match {
      case OwnerRepoBranch(_, _, branch) => baseWs.withQueryStringParameters("ref" -> branch)
      case _ => baseWs
    }

    wsMaybeBranch.get().flatMap(okT[JsObject])
  }

  def createOrUpdateFile(ownerRepo: OwnerRepoBase, path: String, contents: String, commitMessage: String, accessToken: String): Future[JsObject] = {
    val json = Json.obj(
      "path" -> path,
      "message" -> commitMessage,
      "content" -> Base64.encodeBase64String(contents.getBytes)
    )

    val jsonWithMaybeBranch = ownerRepo match {
      case _: OwnerRepo => json
      case OwnerRepoBranch(_, _, branch) => json + ("branch" -> JsString(branch))
    }

    val jsonWithMaybeBranchAndMaybeShaFuture = getFile(ownerRepo, path, accessToken).map { file =>
      val sha = (file \ "sha").as[String]
      jsonWithMaybeBranch + ("sha" -> JsString(sha))
    } recover {
      case _: Exception => jsonWithMaybeBranch
    }

    jsonWithMaybeBranchAndMaybeShaFuture.flatMap { jsonWithMaybeBranchAndMaybeSha =>
      ws(s"repos/${ownerRepo.ownerRepo}/contents/$path", accessToken).put(jsonWithMaybeBranchAndMaybeSha).flatMap { response =>
        createdT[JsObject](response).recoverWith {
          case _: Exception => okT[JsObject](response)
        }
      }
    }
  }

  def getPullRequests(head: OwnerRepoBranch, base: OwnerRepoBranch, state: String, accessToken: String): Future[JsArray] = {
    ws(s"repos/${base.ownerRepo}/pulls", accessToken)
      .withQueryStringParameters(
        "state" -> state,
        "head" -> s"${head.owner}/${head.repo}:${head.branch}",
        "base" -> base.branch
      )
      .get()
      .flatMap(okT[JsArray])
  }

  def createOrGetPullRequest(head: OwnerRepoBase, base: OwnerRepoBase, title: String, accessToken: String): Future[JsObject] = {

    val headBranchFuture = head match {
      case _: OwnerRepo => defaultBranch(head, accessToken).map(OwnerRepoBranch(head.owner, head.repo, _))
      case orb: OwnerRepoBranch => Future.successful(orb)
    }

    val baseBranchFuture = base match {
      case _: OwnerRepo => defaultBranch(base, accessToken).map(OwnerRepoBranch(base.owner, base.repo, _))
      case orb: OwnerRepoBranch => Future.successful(orb)
    }

    def json(head: OwnerRepoBranch, base: OwnerRepoBranch): JsObject = {
      Json.obj(
        "title" -> title,
        "head" -> s"${head.owner}:${head.branch}",
        "base" -> base.branch
      )
    }

    for {
      headBranch <- headBranchFuture
      baseBranch <- baseBranchFuture
      pullRequests <- getPullRequests(headBranch, baseBranch, "open", accessToken)
      pullRequest <- pullRequests.value.headOption.flatMap(_.asOpt[JsObject]).fold {
        // create a pull request
        ws(s"repos/${base.ownerRepo}/pulls", accessToken).post(json(headBranch, baseBranch)).flatMap(createdT[JsObject])
      } (Future.successful)
    } yield pullRequest
  }

  def repoBranches(ownerRepo: OwnerRepoBase, accessToken: String): Future[Set[String]] = {
    ws(s"repos/${ownerRepo.ownerRepo}/branches", accessToken).get().flatMap(
      okT[Set[String]](_)(Reads.set[String]((__ \ "name").read))
    )
  }

  def defaultBranch(ownerRepo: OwnerRepoBase, accessToken: String): Future[String] = {
    repo(ownerRepo, accessToken).map { repoInfo =>
      (repoInfo \ "default_branch").as[String]
    }
  }

  // gets specified branch or default branch
  def repoBranch(ownerRepo: OwnerRepoBase, accessToken: String): Future[JsObject] = {
    val branchFuture = ownerRepo match {
      case _: OwnerRepo => defaultBranch(ownerRepo, accessToken)
      case OwnerRepoBranch(_, _, branch) => Future.successful(branch)
    }

    branchFuture.flatMap { branch =>
      ws(s"repos/${ownerRepo.ownerRepo}/branches/$branch", accessToken).get().flatMap(okT[JsObject])
    }
  }

  def repoCreateBranch(base: OwnerRepoBase, name: String, accessToken: String): Future[JsObject] = {
    repoBranch(base, accessToken).flatMap { branchInfo =>
      val sha = (branchInfo \ "commit" \ "sha").as[String]

      val json = Json.obj(
        "ref" -> s"refs/heads/$name",
        "sha" -> sha
      )

      ws(s"repos/${base.ownerRepo}/git/refs", accessToken).post(json).flatMap(createdT[JsObject])
    }
  }

  def createFork(base: OwnerRepoBase, accessToken: String): Future[JsObject] = {
    ws(s"repos/${base.ownerRepo}/forks", accessToken).execute(HttpVerbs.POST).flatMap(statusT[JsObject](Status.ACCEPTED, _))
  }

  def replaceTemplateParams(template: String, params: Map[String, String]): String = {
    params.foldLeft(template) { case (currentTemplate, (key, value)) =>
      currentTemplate.replaceAllLiterally(s"[$key]", value)
    }
  }

  // given a repo it creates a new branch (if privledges allow) or a new fork
  def repoCreateBranchOrFork(base: OwnerRepoBase, accessToken: String): Future[OwnerRepoBase] = {
    user(accessToken).flatMap { userInfo =>
      val userLogin = (userInfo \ "login").as[String]
      repo(base, accessToken).flatMap { repoInfo =>
        val pushPermission = (repoInfo \ "permissions" \ "push").as[Boolean]
        if (pushPermission) {
          repoBranches(base, accessToken).flatMap { branches =>
            val patchPrefix = userLogin + "-patch-"
            val patchNum: Int = Try {
              val highestPatch = branches.filter(_.startsWith(patchPrefix)).maxBy { name =>
                Try(name.stripPrefix(patchPrefix).toInt).getOrElse(0)
              }

              highestPatch.stripPrefix(patchPrefix).toInt + 1
            } getOrElse 0

            val branchName = patchPrefix + patchNum
            repoCreateBranch(base, branchName, accessToken).map { _ =>
              OwnerRepoBranch(base.owner, base.repo, branchName)
            }
          }
        }
        else {
          createFork(base, accessToken).map { forkInfo =>
            val ownerRepo = (forkInfo \ "full_name").as[String]
            OwnerRepo(ownerRepo)
          }
        }
      }
    }
  }

  def createLicensePullRequest(ownerRepo: OwnerRepoBase, licenseKey: String, templateParams: Map[String, String], accessToken: String): Future[JsObject] = {
    for {
      licenseInfo <- license(licenseKey, accessToken)
      licenseName = (licenseInfo \ "name").as[String]
      licenseTemplate = (licenseInfo \ "body").as[String]
      licenseText = replaceTemplateParams(licenseTemplate, templateParams)

      branchOrFork <- repoCreateBranchOrFork(ownerRepo, accessToken)

      // sometimes it takes time for the fork to be ready so wait until the repo is ready
      _ <- waitFor(getAllGitRefs(branchOrFork, accessToken), 1.seconds, 10)
      _ <- waitFor(getCommits(branchOrFork, accessToken), 1.seconds, 10)

      _ <- createOrUpdateFile(branchOrFork, "LICENSE", licenseText, s"Add $licenseName", accessToken)

      pullRequest <- createOrGetPullRequest(branchOrFork, ownerRepo, "Add License", accessToken)
    } yield pullRequest
  }

  private def waitFor[T](block: => Future[T], pollInterval: FiniteDuration, maxPolls: Int, pollNumber: Int = 0): Future[T] = {
    val currentPoll = pollNumber + 1

    if (currentPoll < maxPolls) {
      block.recoverWith {
        case _ => futures.delayed(pollInterval)(waitFor(block, pollInterval, maxPolls, currentPoll))
      }
    }
    else {
      Future.failed(new TimeoutException("Max polls reached"))
    }
  }

  private def ok(response: WSResponse): Future[Unit] = status(Status.OK, response)

  private def okT[T](response: WSResponse)(implicit r: Reads[T]): Future[T] = statusT[T](Status.OK, response)

  private def created(response: WSResponse): Future[JsValue] = statusT[JsValue](Status.CREATED, response)

  private def createdT[T](response: WSResponse)(implicit r: Reads[T]): Future[T] = statusT[T](Status.CREATED, response)

  private def nocontent(response: WSResponse): Future[Unit] = status(Status.NO_CONTENT, response)

  private def statusT[T](statusCode: Int, response: WSResponse)(implicit r: Reads[T]): Future[T] = {
    if (response.status == statusCode) {
      response.json.asOpt[T].fold {
        Future.failed[T](GitHub.InvalidResponseBody(response.body))
      } (Future.successful)
    } else {
      val messageTry = Try((response.json \ "message").as[String])
      Future.failed(GitHub.IncorrectResponseStatus(statusCode, response.status, messageTry.getOrElse(response.body)))
    }
  }

  private def status(statusCode: Int, response: WSResponse): Future[Unit] = {
    if (response.status == statusCode) {
      Future.successful(Unit)
    } else {
      val messageTry = Try((response.json \ "message").as[String])
      Future.failed(GitHub.IncorrectResponseStatus(statusCode, response.status, messageTry.getOrElse(response.body)))
    }
  }

}

object GitHub {

  def parseOwnerRepo(ownerRepo: String): (String, String) = {
    val parts = ownerRepo.split("/")
    (parts(0), parts(1))
  }

  sealed trait OwnerRepoBase {
    val owner: String
    val repo: String

    def ownerRepo: String = owner + "/" + repo
  }

  case class OwnerRepo(owner: String, repo: String) extends OwnerRepoBase

  object OwnerRepo {
    def apply(ownerRepo: String): OwnerRepo = {
      val (owner, repo) = parseOwnerRepo(ownerRepo)
      apply(owner, repo)
    }
  }

  case class OwnerRepoBranch(owner: String, repo: String, branch: String) extends OwnerRepoBase

  object OwnerRepoBranch {
    def apply(ownerRepo: String, branch: String): OwnerRepoBranch = {
      val (owner, repo) = parseOwnerRepo(ownerRepo)
      apply(owner, repo, branch)
    }
  }

  case class AuthorLoginNotFound(sha: String, author: JsObject) extends Exception {
    val maybeName: Option[String] = (author \ "name").asOpt[String]
    override def getMessage: String = "Commit authors must be associated with GitHub users"
  }

  case class IncorrectResponseStatus(expectedStatusCode: Int, actualStatusCode: Int, message: String) extends Exception {
    override def getMessage: String = s"Expected status code $expectedStatusCode but got $actualStatusCode - $message"
  }

  case class InvalidResponseBody(body: String) extends Exception {
    override def getMessage: String = "Response body was not in the expected form"
  }

}
