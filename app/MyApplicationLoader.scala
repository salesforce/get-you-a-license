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

import controllers.{AssetsComponents, Main}
import filters.OnlyHttpsFilter
import org.webjars.play.{RequireJS, WebJarAssets, WebJarsUtil}
import play.api.{Application, ApplicationLoader, BuiltInComponentsFromContext}
import play.api.ApplicationLoader.Context
import play.api.libs.concurrent.{DefaultFutures, Futures}
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.filters.HttpFiltersComponents
import play.filters.csrf.CSRFFilter
import router.Routes
import utils.GitHub

class MyApplicationLoader extends ApplicationLoader {
  def load(context: Context): Application = {
    new MyComponents(context).application
  }
}

class MyComponents(context: Context) extends BuiltInComponentsFromContext(context) with AhcWSComponents with AssetsComponents with HttpFiltersComponents {

  override def httpFilters: Seq[EssentialFilter] = {
    super.httpFilters.filterNot(_.isInstanceOf[CSRFFilter]) :+ new OnlyHttpsFilter(environment)
  }

  lazy val futures = new DefaultFutures(actorSystem)

  lazy val webJarsUtil = new WebJarsUtil(context.initialConfiguration, context.environment)

  lazy val indexView = new views.html.index(webJarsUtil)
  lazy val orgView = new views.html.org(webJarsUtil)

  lazy val gitHub = new GitHub(context.initialConfiguration, wsClient, futures)
  lazy val main = new Main(gitHub, controllerComponents)(indexView, orgView)

  lazy val requireJs = new RequireJS(webJarsUtil)
  lazy val webJarAssets = new WebJarAssets(httpErrorHandler, assetsMetadata)
  lazy val webJarsRoutes = new webjars.Routes(httpErrorHandler, requireJs, webJarAssets)

  lazy val router: Router = new Routes(httpErrorHandler, main, assets, webJarsRoutes)
}
