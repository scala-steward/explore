// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package explore.common

import cats.MonadError
import cats.data.OptionT
import cats.effect.Effect
import cats.syntax.all._
import clue.macros.GraphQL
import clue.{ GraphQLClient, GraphQLOperation }
import crystal.react.implicits._
import explore.GraphQLSchemas.UserPreferencesDB
import explore.model.ResizableSection
import explore.model.GridLayoutSection
import explore.model.layout._
import japgolly.scalajs.react.Callback
import japgolly.scalajs.react.raw.JsNumber
import lucuma.core.model.User
import lucuma.core.util.Gid
import clue.data.Input
import react.common._
import react.gridlayout.{ BreakpointName => _, _ }
import scala.collection.immutable.SortedMap

object UserPreferencesQueries {

  /**
   * Query to create a user, this is called when the app is started.
   * If the user exists the error is ignored
   */
  @GraphQL
  object UserInsertMutation extends GraphQLOperation[UserPreferencesDB] {
    val document = """
      mutation insert_user($id: String) {
        insert_lucuma_user_one(
          object: {
            user_id: $id
          },
          on_conflict: {
            update_columns: [],
            constraint: lucuma_user_pkey
          }
        ) {
          user_id
          }
        }
    """
  }

  /**
   * Update the width of a given area/user
   */
  @GraphQL
  object UserWidthsCreation extends GraphQLOperation[UserPreferencesDB] {
    val document = """
      mutation update_area_width($item: explore_resizable_width_insert_input!) {
        insert_explore_resizable_width_one(
          object: $item,
          on_conflict: {
            constraint: explore_resizable_width_pkey,
            update_columns: width
          }
        ) {
          user_id
          }
        }
   """

    def storeWidthPreference[F[_]: Effect](
      userId:  Option[User.Id],
      section: ResizableSection,
      width:   Int
    )(implicit
      cl:      GraphQLClient[F, UserPreferencesDB]
    ): Callback =
      userId.map { i =>
        UserWidthsCreation
          .execute[F](
            WidthUpsertInput(i, section, width)
          )
          .attempt
          .runAsyncAndForgetCB
      }.getOrEmpty

  }

  /**
   * Read the stored width of an area
   */
  @GraphQL(debug = false)
  object UserAreaWidths extends GraphQLOperation[UserPreferencesDB] {
    val document = """
      query area_width($user_id: String!, $section: resizable_area!) {
        explore_resizable_width_by_pk(
          section: $section,
          user_id: $user_id
        ) {
          width
        }
      }
    """

    // Gets the width of a section.
    // This is coded to return a default in case
    // there is no data or errors
    def queryWithDefault[F[_]: MonadError[*[_], Throwable]](
      userId:       Option[User.Id],
      area:         ResizableSection,
      defaultValue: Int
    )(implicit cl:  GraphQLClient[F, UserPreferencesDB]): F[Int] =
      (for {
        uid <- OptionT.fromOption[F](userId)
        w   <-
          OptionT
            .liftF[F, Option[Int]] {
              query[F](Gid[User.Id].show(uid), area.value)
                .map { r =>
                  r.explore_resizable_width_by_pk.flatMap(_.width)
                }
                .recover(_ => none)
            }
      } yield w).value.map(_.flatten.getOrElse(defaultValue))
  }

  /**
   * Read the grid layout for a given section
   */
  @GraphQL(debug = false)
  object UserGridLayoutQuery extends GraphQLOperation[UserPreferencesDB] {
    val document = """
      query layout_positions($criteria: grid_layout_positions_bool_exp!) {
        grid_layout_positions(where: $criteria) {
          breakpoint_name
          height
          width
          x
          y
          tile
        }
      }"""

    def positions2LayoutMap(
      g: (BreakpointName, List[Data.GridLayoutPositions])
    ): (react.gridlayout.BreakpointName, (JsNumber, JsNumber, Layout)) =
      breakpointNameFromString(g._1) -> ((1,
                                          1,
                                          Layout(
                                            g._2.map(p =>
                                              LayoutItem(p.width, p.height, p.x, p.y, p.tile)
                                            )
                                          )
                                         )
      )

    // Gets the layout of a section.
    // This is coded to return a default in case
    // there is no data or errors
    def queryWithDefault[F[_]: MonadError[*[_], Throwable]](
      userId:       Option[User.Id],
      area:         GridLayoutSection,
      defaultValue: LayoutsMap
    )(implicit cl:  GraphQLClient[F, UserPreferencesDB]): F[LayoutsMap] =
      (for {
        uid <- OptionT.fromOption[F](userId)
        c   <-
          OptionT.pure(
            GridLayoutPositionsBoolExp(user_id =
              Input(StringComparisonExp(Input(Gid[User.Id].show(uid))))
            )
          )
        w   <-
          OptionT
            .liftF[F, Map[BreakpointName, List[Data.GridLayoutPositions]]] {
              query[F](c)
                .map { r =>
                  r.grid_layout_positions.groupBy(_.breakpoint_name)
                }
                .recover(_ => Map.empty)
            }
        r   <- OptionT.pure(w.map(positions2LayoutMap))
      } yield SortedMap(r.toList: _*)).value.map(_.getOrElse(defaultValue))
  }

  @GraphQL(debug = true)
  object UserGridLayoutUpsert extends GraphQLOperation[UserPreferencesDB] {
    val document = """
      mutation insert_layout_positions($objects: [grid_layout_positions_insert_input!]! = {}) {
        insert_grid_layout_positions(objects: $objects, on_conflict: {
          constraint: grid_layout_positions_pkey,
          update_columns: [width, height, x, y]
        }) {
          affected_rows
        }
      }"""

    def storeLayoutsPreference[F[_]: Effect](
      userId:  Option[User.Id],
      section: GridLayoutSection,
      layouts: Layouts
    )(implicit
      cl:      GraphQLClient[F, UserPreferencesDB]
    ): Callback =
      userId.map { uid =>
        UserGridLayoutUpsert
          .execute[F](
            layouts.layouts.flatMap { bl =>
              bl.layout.l.map(i =>
                GridLayoutPositionsInsertInput(
                  user_id = Input(Gid[User.Id].show(uid)),
                  section = Input(section.value),
                  breakpoint_name = Input(bl.name.name),
                  width = Input(i.w.toInt),
                  height = Input(i.h.toInt),
                  x = Input(i.x.toInt),
                  y = Input(i.y.toInt),
                  tile = Input(i.i.getOrElse(""))
                )
              )
            }
          )
          .attempt
          .runAsyncAndForgetCB
      }.getOrEmpty

  }
}
