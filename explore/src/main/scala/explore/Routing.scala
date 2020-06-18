// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package explore

import cats.implicits._
import crystal.react.implicits._
import explore.model.Page
import explore.model.Page._
import explore.model._
import gem.Observation
import japgolly.scalajs.react.Callback
import japgolly.scalajs.react.MonocleReact._
import japgolly.scalajs.react.extra.router._
import japgolly.scalajs.react.vdom.VdomElement
import monocle.Prism
import monocle.Iso
import java.util.UUID

sealed trait ElementItem  extends Product with Serializable
case object IconsElement  extends ElementItem
case object LabelsElement extends ElementItem

object Routing {

  private val obsPageIso: Iso[ExploreObservation.Id, ObsPage] =
    Iso[ExploreObservation.Id, ObsPage](ObsPage.apply)(_.obsId)

  private val targetPageIso: Iso[SiderealTarget.Id, TargetPage] =
    Iso[SiderealTarget.Id, TargetPage](TargetPage.apply)(_.targetId)

  private val targetObsPageIso: Iso[ExploreObservation.Id, TargetsObsPage] =
    Iso[ExploreObservation.Id, TargetsObsPage](TargetsObsPage.apply)(_.obsId)

  val config: RouterWithPropsConfig[Page, View[RootModel]] =
    RouterWithPropsConfigDsl[Page, View[RootModel]].buildConfig { dsl =>
      import dsl._

      (emptyRule
        | staticRoute(root, HomePage) ~> renderP(view => HomeComponent(view))
        | dynamicRouteCT(("/obs" / uuid).xmapL(obsPageIso)) ~> renderP(view => HomeComponent(view))
        | dynamicRouteCT(("/target" / uuid).xmapL(targetPageIso)) ~> renderP(view =>
          HomeComponent(view)
        )
        | dynamicRouteCT(("/target/obs" / uuid).xmapL(targetObsPageIso)) ~> renderP(view =>
          HomeComponent(view)
        )
        | staticRoute("/configurations", ConfigurationsPage) ~> render(UnderConstruction())
        | staticRoute("/constraints", ConstraintsPage) ~> render(UnderConstruction()))
        .notFound(redirectToPage(HomePage)(SetRouteVia.HistoryPush))
        .verify(
          HomePage,
          ObsPage(UUID.randomUUID),
          TargetPage(UUID.randomUUID),
          TargetsObsPage(UUID.randomUUID),
          ConfigurationsPage,
          ConstraintsPage
        )
        .onPostRenderP {
          case (prev, next, view) if next.some =!= prev =>
            Callback
              .log(
                s"Routing.onPostRender triggered [$prev] => [$next]"
              ) >>
              view.zoomL(RootModelRouting.lens).set(next).runInCB
          case _                                        => Callback.empty
        }
        .renderWithP(layout)
    // .logToConsole
    }

  private def layout(
    c: RouterCtl[Page],
    r: ResolutionWithProps[Page, View[RootModel]]
  ): View[RootModel] => VdomElement =
    OTLayout(c, r)
}
