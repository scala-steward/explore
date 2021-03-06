// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package explore.config

import cats.effect.IO
import cats.syntax.all._
import coulomb.Quantity
import coulomb.refined._
import crystal.Pot
import crystal.ViewF
import crystal.react.implicits._
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import explore.AppCtx
import explore.common.ObsQueries._
import explore.common.ScienceRequirementsQueries._
import explore.components.HelpIcon
import explore.components.Tile
import explore.components.ui.ExploreStyles
import explore.components.undo.UndoButtons
import explore.implicits._
import explore.model.ImagingConfigurationOptions
import explore.model.SpectroscopyConfigurationOptions
import explore.model.enum.FocalPlane
import explore.model.enum.ScienceMode
import explore.model.enum.SpectroscopyCapabilities
import explore.model.reusability._
import explore.modes.SpectroscopyModesMatrix
import explore.undo.UndoContext
import explore.undo.UndoStacks
import fs2._
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import lucuma.core.math.Wavelength
import lucuma.core.math.units.Micrometer
import lucuma.core.model.Observation
import lucuma.core.optics.syntax.lens._
import lucuma.core.util.Display
import lucuma.ui.forms.EnumViewSelect
import lucuma.ui.reusability._
import monocle.Focus
import monocle.Iso
import monocle.Lens
import react.common._
import react.semanticui.collections.form.Form
import react.semanticui.sizes._
import sttp.client3._
import sttp.client3.impl.cats.FetchCatsBackend
import sttp.model._

import scala.concurrent.duration._

final case class ConfigurationPanel(
  obsId:               Observation.Id,
  scienceRequirements: View[ScienceRequirementsData],
  undoStacks:          View[UndoStacks[IO, ScienceRequirementsData]],
  renderInTitle:       Tile.RenderInTitle
)(implicit val ctx:    AppContextIO)
    extends ReactProps[ConfigurationPanel](ConfigurationPanel.component)

object ConfigurationPanel {
  type Props = ConfigurationPanel

  implicit val modeDisplay: Display[ScienceMode] = Display.byShortName {
    case ScienceMode.Spectroscopy => "Spectroscopy"
    case ScienceMode.Imaging      => "Imaging"
  }

  implicit val specCapabDisplay: Display[SpectroscopyCapabilities] = Display.by(_.label, _.label)
  implicit val focaLPlaneDisplay: Display[FocalPlane]              = Display.by(_.label, _.label)
  implicit val propsReuse: Reusability[Props]                      = Reusability.derive
  implicit val stateReuse: Reusability[State]                      = Reusability.never

  final case class State(
    mode:           ScienceMode,
    imagingOptions: ImagingConfigurationOptions,
    matrix:         Pot[SpectroscopyModesMatrix]
  )
  object State {
    val mode: Lens[State, ScienceMode]                           = Focus[State](_.mode)
    val imagingOptions: Lens[State, ImagingConfigurationOptions] = Focus[State](_.imagingOptions)
    val matrix: Lens[State, Pot[SpectroscopyModesMatrix]]        = Focus[State](_.matrix)
  }

  val dataIso: Iso[SpectroscopyRequirementsData, SpectroscopyConfigurationOptions] =
    Iso[SpectroscopyRequirementsData, SpectroscopyConfigurationOptions] { s =>
      def wavelengthToMicro(w: Wavelength) = w.micrometer.toValue[BigDecimal]

      val op = for {
        _ <- SpectroscopyConfigurationOptions.wavelengthQ := s.wavelength.map(wavelengthToMicro)
        _ <- SpectroscopyConfigurationOptions.resolution := s.resolution
        _ <- SpectroscopyConfigurationOptions.signalToNoise := s.signalToNoise
        _ <- SpectroscopyConfigurationOptions.signalToNoiseAtQ := s.signalToNoiseAt.map(
               wavelengthToMicro
             )
        _ <- SpectroscopyConfigurationOptions.wavelengthRangeQ := s.wavelengthRange.map(
               wavelengthToMicro
             )
        _ <- SpectroscopyConfigurationOptions.focalPlane := s.focalPlane
        _ <- SpectroscopyConfigurationOptions.focalPlaneAngle := s.focalPlaneAngle
        _ <- SpectroscopyConfigurationOptions.capabilities := s.capabilities
      } yield ()
      op.runS(SpectroscopyConfigurationOptions.Default).value
    } { s =>
      def microToWavelength(m: Quantity[BigDecimal, Micrometer]) =
        Wavelength.decimalMicrometers.getOption(m.value)

      val op = for {
        _ <- SpectroscopyRequirementsData.wavelength := s.wavelengthQ.flatMap(microToWavelength)
        _ <- SpectroscopyRequirementsData.resolution := s.resolution
        _ <- SpectroscopyRequirementsData.signalToNoise := s.signalToNoise
        _ <- SpectroscopyRequirementsData.signalToNoiseAt := s.signalToNoiseAtQ.flatMap(
               microToWavelength
             )
        _ <- SpectroscopyRequirementsData.wavelengthRange := s.wavelengthRangeQ.flatMap(
               microToWavelength
             )
        _ <- SpectroscopyRequirementsData.focalPlane := s.focalPlane
        _ <- SpectroscopyRequirementsData.focalPlaneAngle := s.focalPlaneAngle
        _ <- SpectroscopyRequirementsData.capabilities := s.capabilities
      } yield ()
      op.runS(SpectroscopyRequirementsData()).value
    }

  class Backend($ : BackendScope[Props, State]) {
    private def renderFn(
      props:        Props,
      state:        State,
      undoCtx:      UndoCtx[ScienceRequirementsData]
    )(implicit ctx: AppContextIO): VdomNode = {
      val undoViewSet = UndoView(props.obsId, undoCtx)

      def mode           = undoViewSet(ScienceRequirementsData.mode, UpdateScienceRequirements.mode)
      val isSpectroscopy = mode.get === ScienceMode.Spectroscopy

      val spectroscopy = undoViewSet(
        ScienceRequirementsData.spectroscopyRequirements,
        UpdateScienceRequirements.spectroscopyRequirements
      )
      val imaging      = ViewF.fromStateSyncIO($).zoom(State.imagingOptions)

      <.div(
        ExploreStyles.ConfigurationGrid,
        props.renderInTitle(<.span(ExploreStyles.TitleStrip)(UndoButtons(undoCtx))),
        Form(size = Small)(
          ExploreStyles.Grid,
          ExploreStyles.Compact,
          ExploreStyles.ExploreForm,
          ExploreStyles.ConfigurationForm,
          <.label("Mode", HelpIcon("configuration/mode.md")),
          EnumViewSelect(id = "configuration-mode", value = mode),
          SpectroscopyConfigurationPanel(spectroscopy.as(dataIso))
            .when(isSpectroscopy),
          ImagingConfigurationPanel(imaging).unless(isSpectroscopy)
        ),
        SpectroscopyModesTable(
          state.matrix.toOption
            .map(
              _.filtered(
                focalPlane = spectroscopy.get.focalPlane,
                capabilities = spectroscopy.get.capabilities,
                wavelength = spectroscopy.get.wavelength,
                slitWidth = spectroscopy.get.focalPlaneAngle,
                resolution = spectroscopy.get.resolution,
                range = spectroscopy.get.wavelengthRange
                  .map(_.micrometer.toValue[BigDecimal].toRefined[Positive])
              )
            )
            .getOrElse(Nil),
          spectroscopy.get.focalPlane,
          spectroscopy.get.wavelength
        ).when(isSpectroscopy)
      )
    }

    def render(props: Props, state: State) = AppCtx.using { implicit appCtx =>
      renderFn(
        props,
        state,
        UndoContext(props.undoStacks, props.scienceRequirements)
      )
    }
  }

  def load(uri: Uri): IO[SpectroscopyModesMatrix] = {
    val backend = FetchCatsBackend[IO]()
    basicRequest
      .get(uri)
      .readTimeout(5.seconds)
      .send(backend)
      .flatMap {
        _.body.fold(_ => SpectroscopyModesMatrix.empty.pure[IO],
                    s => SpectroscopyModesMatrix[IO](Stream.emit(s))
        )
      }
  }

  protected val component =
    ScalaComponent
      .builder[Props]
      .initialState(
        State(ScienceMode.Spectroscopy, ImagingConfigurationOptions.Default, Pot.pending)
      )
      .renderBackend[Backend]
      .componentDidMount { $ =>
        implicit val ctx = $.props.ctx
        load(uri"/instrument_spectroscopy_matrix.csv").flatMap { m =>
          $.modStateIn[IO](State.matrix.replace(Pot(m)))
        }.runAsyncAndForgetCB
      }
      .configure(Reusability.shouldComponentUpdate)
      .build
}
