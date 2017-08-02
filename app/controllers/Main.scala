/*
 * Copyright (c) 2017, salesforce.com, inc.
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

package controllers

import akka.util.ByteString
import play.api.Configuration
import play.api.http.HttpEntity
import play.api.libs.json.{JsObject, Json, JsonValidationError, Reads, __}
import play.api.libs.functional.syntax._
import play.api.mvc._
import utils.GitHub
import utils.GitHub.{OwnerRepo, OwnerRepoBase, parseOwnerRepo}

import scala.concurrent.{ExecutionContext, Future}


class Main(gitHub: GitHub, cc: ControllerComponents) (implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val ACCESS_TOKEN = "access_token"
  private val gitHubScopes = Set("public_repo")

  def index = Action { request =>
    Ok(views.html.index(gitHubAuthUrl, gitHub.clientId, redirectUri(request), gitHubScopes))
  }

  def gitHubOauthCallback(code: String, state: String) = Action.async {
    gitHub.accessToken(code).map { accessToken =>
      Redirect(routes.Main.gitHubOrg(state)).flashing(ACCESS_TOKEN -> accessToken)
    } recover {
      case e: Exception => InternalServerError(e.getMessage)
    }
  }

  def gitHubOrg(org: String) = Action.async { request =>
    request.flash.get(ACCESS_TOKEN).fold {
      val params = Map(
        "client_id" -> gitHub.clientId,
        "redirect_uri" -> redirectUri(request),
        "scope" -> gitHubScopes.mkString(","),
        "state" -> org
      ).mapValues(Seq(_))

      Future.successful(Redirect(gitHubAuthUrl, params))
    } { accessToken =>
      gitHub.licenses(accessToken).flatMap { licensesJson =>
        val licenses = licensesJson.value.map { licenseJson =>
          (licenseJson \ "key").as[String] -> (licenseJson \ "name").as[String]
        }.toMap

        gitHub.orgOrUserRepos(org, accessToken).map(_.as[Seq[Repo]]).map { repos =>
          val noForks = repos.filterNot(_.fork)
          Ok(views.html.org(org, noForks, licenses, accessToken))
        }
      }
    }
  }

  def licensePullRequest = Action.async(parse.json) { request =>
    request.headers.get("X-GITHUB-TOKEN").fold {
      Future.successful(Unauthorized("GitHub Access Token Not Set"))
    } { accessToken =>
      val orgRepo = (request.body \ "orgRepo").as[String]
      val licenseKey = (request.body \ "licenseKey").as[String]

      gitHub.licenseParams(licenseKey, accessToken).flatMap { paramNames =>
        val templateParams = paramNames.foldLeft(Map.empty[String, String]) { case (params, paramName) =>
          val paramValue = (request.body \ paramName).as[String]
          params + (paramName -> paramValue)
        }

        gitHub.createLicensePullRequest(OwnerRepo(orgRepo), licenseKey, templateParams, accessToken).map { pullRequest =>
          Ok(pullRequest)
        }
      }
    }
  }

  def licenseParams(key: String) = Action.async { request =>
    request.headers.get("X-GITHUB-TOKEN").fold {
      Future.successful(Unauthorized("GitHub Access Token Not Set"))
    } { accessToken =>
      gitHub.licenseParams(key, accessToken).map { params =>
        Ok(Json.toJson(params))
      }
    }
  }

  private val gitHubAuthUrl: String = "https://gitHub.com/login/oauth/authorize"

  private def redirectUri(implicit request: RequestHeader): String = {
    routes.Main.gitHubOauthCallback("", "").absoluteURL(request.secure).stripSuffix("?code=&state=")
  }

}

case class Repo(name: String, fullName: String, fork: Boolean, maybeLicense: Option[String])

object Repo {

  implicit val jsReads: Reads[Repo] = (
    (__ \ "name").read[String] ~
    (__ \ "full_name").read[String] ~
    (__ \ "fork").read[Boolean] ~
    (__ \ "license").readNullable[JsObject].collect(JsonValidationError("Could not parse license")) {
      case Some(license) => (license \ "name").asOpt[String]
      case _ => None
    }
  )(Repo.apply _)
}
