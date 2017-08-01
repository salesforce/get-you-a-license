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

package utils

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play._
import play.api.Configuration
import play.api.libs.ws.ahc.AhcWSClient
import play.api.test.Helpers._
import utils.GitHub._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

class GitHubSpec extends PlaySpec with BeforeAndAfterAll {

  implicit lazy val actorSystem = ActorSystem()

  implicit lazy val ec = actorSystem.dispatcher

  implicit lazy val materializer = ActorMaterializer()

  lazy val config = Configuration(ConfigFactory.load())

  lazy val wsClient = AhcWSClient()

  lazy val gitHub = new GitHub(config, wsClient)(ec)

  lazy val gitHubTestToken = sys.env("GITHUB_TEST_TOKEN")
  lazy val gitHubTestOrg = sys.env("GITHUB_TEST_ORG")
  lazy val gitHubTestRepo = sys.env("GITHUB_TEST_REPO")
  lazy val gitHubTestUser = sys.env("GITHUB_TEST_USER")

  lazy val gitHubTestOrgRepo = OwnerRepo(gitHubTestOrg, gitHubTestRepo)

  "GitHub.orgRepos" should {
    "get org repos with a page size of 1" in {
      val repos = await(gitHub.orgOrUserRepos(gitHubTestOrg, gitHubTestToken, 1))
      repos.value must not be 'empty
    }
    "get org repos with a page size of 500" in {
      val repos = await(gitHub.orgOrUserRepos(gitHubTestOrg, gitHubTestToken, 500))
      repos.value must not be 'empty
    }
    "get user repos" in {
      val repos = await(gitHub.orgOrUserRepos(gitHubTestUser, gitHubTestToken))
      repos.value must not be 'empty
    }
  }

  "GitHub.repoLicense" should {
    "work with a license" in {
      val license = await(gitHub.repoLicense(OwnerRepo("webjars/webjars"), gitHubTestToken))
      license must contain ("MIT License")
    }
    "work without a license" in {
      val license = await(gitHub.repoLicense(OwnerRepo("jamesward/space-echo"), gitHubTestToken))
      license must be (empty)
    }
  }

  "GitHub.licenses" should {
    "work" in {
      val licenses = await(gitHub.licenses(gitHubTestToken))
      licenses.value must not be empty
    }
  }

  "GitHub.license" should {
    "work" in {
      val licenseInfo = await(gitHub.license("mit", gitHubTestToken))
      (licenseInfo \ "name").as[String] must equal ("MIT License")
    }
    "not work with invalid license" in {
      an [Exception] must be thrownBy await(gitHub.license("foo", gitHubTestToken))
    }
  }

  "GitHub.licenseParams" should {
    "work" in {
      val params = await(gitHub.licenseParams("mit", gitHubTestToken))
      params must equal (Set("year", "fullname"))
    }
  }

  "GitHub.repoBranches" should {
    "work" in {
      val branches = await(gitHub.repoBranches(OwnerRepo("webjars/webjars"), gitHubTestToken))
      branches must contain ("master")
    }
  }

  "GitHub.repoBranch" should {
    "work" in {
      val branch = await(gitHub.repoBranch(OwnerRepoBranch("webjars", "webjars", "master"), gitHubTestToken))
      (branch \ "name").as[String] must equal ("master")
    }
  }

  "GitHub.repoCreateBranch" should {
    "work" in {
      val name = Random.alphanumeric.take(8).mkString

      val create = await(gitHub.repoCreateBranch(gitHubTestOrgRepo, name, gitHubTestToken))

      (create \ "ref").as[String] must equal (s"refs/heads/$name")
    }
  }

  "GitHub.repoCreateBranchOrFork" should {
    "work with an accessible repo" in {
      val branchOrFork = await(gitHub.repoCreateBranchOrFork(gitHubTestOrgRepo, gitHubTestToken))
      branchOrFork mustBe a[OwnerRepoBranch]
    }
    "work with an inaccessible repo" in {
      val branchOrFork = await(gitHub.repoCreateBranchOrFork(OwnerRepo("webjars/webjars"), gitHubTestToken))
      branchOrFork.owner must equal (gitHubTestUser)
      branchOrFork.repo must equal ("webjars")
    }
  }

  "GitHub.createFile" should {
    "work" in {
      val path = Random.alphanumeric.take(8).mkString
      val create = await(gitHub.createFile(gitHubTestOrgRepo, path, "test", "test", gitHubTestToken))
      (create \ "commit" \ "sha").asOpt[String] must be (defined)
    }
  }

  "GitHub.createLicensePullRequest" should {
    "create a new Pull Request with a license" in {
      val templateParams = Map("year" -> "2017", "fullname" -> "Foo Bar")
      val pullRequest = await(gitHub.createLicensePullRequest(gitHubTestOrgRepo, "mit", templateParams, gitHubTestToken))
      (pullRequest \ "state").as[String] must equal ("open")
    }
    "not create a new pull request with an invalid license" in {
      an [Exception] should be thrownBy await(gitHub.createLicensePullRequest(gitHubTestOrgRepo, "foo", Map.empty[String, String], gitHubTestToken))
    }
  }

  override def afterAll {
    wsClient.close()
    Await.result(actorSystem.terminate(), 1.minute)
  }

}
