// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package explore.targeteditor

import cats.Order._
import cats.syntax.all._
import crystal.ViewF
import crystal.react.implicits._
import eu.timepit.refined.auto._
import explore.Icons
import explore.components.ui.ExploreStyles
import explore.implicits._
import explore.model.display._
import explore.utils.ReactTableHelpers
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import lucuma.core.enum.MagnitudeBand
import lucuma.core.math.MagnitudeValue
import lucuma.core.model.Magnitude
import lucuma.core.model.Target
import lucuma.ui.forms.EnumViewSelect
import lucuma.ui.optics.ChangeAuditor
import lucuma.ui.optics.ValidFormatInput
import lucuma.ui.reusability._
import monocle.Focus
import react.common.ReactProps
import react.semanticui.collections.form.Form
import react.semanticui.collections.table._
import react.semanticui.elements.button.Button
import react.semanticui.elements.segment.Segment
import react.semanticui.elements.segment.SegmentAttached
import react.semanticui.sizes._
import reactST.reactTable.SUITable
import reactST.reactTable.TableMaker
import reactST.reactTable.mod.SortingRule

import scala.collection.immutable.HashSet

import scalajs.js.JSConverters._

final case class MagnitudeForm(
  targetId:   Target.Id,
  magnitudes: View[List[Magnitude]],
  disabled:   Boolean
) extends ReactProps[MagnitudeForm](MagnitudeForm.component)

object MagnitudeForm {
  type Props = MagnitudeForm

  protected case class State(usedBands: Set[MagnitudeBand], newBand: Option[MagnitudeBand])

  object State {
    val usedBands = Focus[State](_.usedBands)
    val newBand   = Focus[State](_.newBand)
  }

  implicit val propsReuse: Reusability[Props] = Reusability.derive
  implicit val stateReuse: Reusability[State] = Reusability.derive

  private val MagTable = TableMaker[View[Magnitude]].withSort

  import MagTable.syntax._

  private val MagTableComponent = new SUITable(MagTable)

  protected class Backend($ : BackendScope[Props, State]) {
    def render(props: Props, state: State) = {
      val newBandView: Option[View[MagnitudeBand]] =
        state.newBand.map(band =>
          ViewF(
            band,
            (mod, cb) =>
              $.modStateInSyncIO(State.newBand.some.modify(mod), _.newBand.map(cb).orEmpty)
          )
        )

      val deleteButton = Button(
        size = Small,
        compact = true,
        clazz = ExploreStyles.DeleteButton
      )(
        Icons.Trash
      )

      val deleteFn: View[Magnitude] => Callback =
        mag => props.magnitudes.mod(_.filterNot(_.band === mag.get.band))

      val excludeFn: View[Magnitude] => Set[MagnitudeBand] =
        mag => HashSet.from(props.magnitudes.get.map(_.band)) - mag.get.band

      val footer = TableFooter(
        TableRow(
          TableHeaderCell()(^.colSpan := 4)(
            <.div(
              ExploreStyles.MagnitudesTableFooter,
              newBandView.whenDefined { view =>
                val addMagnitude =
                  props.magnitudes.mod(list =>
                    (list :+ Magnitude(MagnitudeValue(0), view.get, view.get.magnitudeSystem))
                      .sortBy(_.band)
                  )

                React.Fragment(
                  EnumViewSelect(
                    id = "NEW_BAND",
                    value = view,
                    exclude = state.usedBands,
                    clazz = ExploreStyles.FlatFormField,
                    disabled = props.disabled
                  ),
                  Button(size = Mini,
                         compact = true,
                         onClick = addMagnitude,
                         disabled = props.disabled
                  )(^.marginLeft := "5px")(
                    Icons.New
                  )
                )

                React.Fragment(
                  EnumViewSelect(
                    id = "NEW_BAND",
                    value = view,
                    exclude = state.usedBands,
                    clazz = ExploreStyles.FlatFormField,
                    disabled = props.disabled
                  ),
                  Button(size = Mini,
                         compact = true,
                         onClick = addMagnitude,
                         disabled = props.disabled
                  )(^.marginLeft := "5px")(
                    Icons.New
                  )
                )
              }
            )
          )
        )
      )

      val columns = List(
        MagTable
          .Column("value", _.zoom(Magnitude.value))
          .setHeader("Value")
          .setCell(
            ReactTableHelpers
              .editableViewColumn(
                Magnitude.value,
                validFormat =
                  ValidFormatInput.fromFormat(MagnitudeValue.fromString, "Invalid magnitude"),
                changeAuditor = ChangeAuditor
                  .fromFormat(MagnitudeValue.fromString)
                  .decimal(3)
                  .allowEmpty,
                disabled = props.disabled
              )
          ),
        MagTable
          .Column("band", _.zoom(Magnitude.band))
          .setHeader("Band")
          .setCell(
            ReactTableHelpers.editableEnumViewColumn(Magnitude.band)(
              disabled = props.disabled,
              excludeFn = Some(excludeFn)
            )
          )
          .setSortByFn(_.get),
        MagTable
          .Column("system", _.zoom(Magnitude.system))
          .setHeader("System")
          .setCell(
            ReactTableHelpers.editableEnumViewColumn(Magnitude.system)(
              disabled = props.disabled
            )
          ),
        MagTable
          .Column[Unit]("delete")
          .setCell(
            ReactTableHelpers.buttonViewColumn(button = deleteButton,
                                               onClick = deleteFn,
                                               disabled = props.disabled,
                                               wrapperClass =
                                                 ExploreStyles.MagnitudesTableDeletButtonWrapper
            )
          )
      ).toJSArray

      val tableState = MagTable.State().setSortByVarargs(SortingRule("band"))

      // Put it inside a form to get the SUI styles right
      Form(as = <.div, size = Small)(
        <.div(
          ExploreStyles.MagnitudesTableSection,
          <.label("Magnitudes"),
          Segment(attached = SegmentAttached.Attached,
                  compact = true,
                  clazz = ExploreStyles.MagnitudesTableContainer
          )(
            tableComponent(
              (
                MagTableComponent(
                  Table(celled = true,
                        selectable = true,
                        striped = true,
                        compact = TableCompact.Very
                  ),
                  header = TableHeader(),
                  footer = footer.vdomElement
                ),
                MagTable
                  .Options(columns, props.magnitudes.toListOfViews(_.band).toJSArray)
                  .setRowIdFn(_.get.band.tag)
                  .setInitialStateFull(tableState)
              )
            )
          )
        )
      )
    }
  }

  val component =
    ScalaComponent
      .builder[Props]
      .getDerivedStateFromPropsAndState[State] { case (props, stateOpt) =>
        val usedBands = HashSet.from(props.magnitudes.get.map(_.band))
        stateOpt match {
          case Some(state) if state.newBand.exists(b => !usedBands.contains(b)) =>
            State.usedBands.replace(usedBands)(state)
          case _                                                                =>
            State(usedBands, MagnitudeBand.all.diff(usedBands.toList).headOption)
        }
      }
      .renderBackend[Backend]
      .configure(Reusability.shouldComponentUpdate)
      .build

  // Horrible hack while we don't fully have hooks.
  // Reusability is handled in class component, instead of the need to useMemo.
  // Table is only rerendered when needed, thus avoiding the loop in react-table when passing unstable columns or data.
  private val tableComponent =
    ScalaFnComponent[(MagTableComponent.Applied, MagTable.OptionsType)] { props =>
      props._1(props._2)
    }
}
