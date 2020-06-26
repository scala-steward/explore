// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package explore.model

import cats.implicits._
import cats.kernel.Eq
import gem.Observation
import monocle.Optional
import monocle.macros.GenPrism
import monocle.macros.Lenses
import monocle.Prism

sealed trait Focused extends Product with Serializable
object Focused {
  @Lenses case class FocusedObs(obsId: ExploreObservation.Id) extends Focused
  @Lenses case class FocusedTarget(targetId: SiderealTarget.Id) extends Focused
  // @Lenses case class FocusedConstraint(constraint: ???) extends Focused
  // @Lenses case class FocusedConfiguration(configuraton: ???) extends Focused

  implicit val eqFocused: Eq[Focused] = Eq.instance {
    case (FocusedObs(a), FocusedObs(b))       => a === b
    case (FocusedTarget(a), FocusedTarget(b)) => a === b
    // case (FocusedConstraint(a), FocusedConstraint(b))       => a === b
    // case (FocusedConfiguration(a), FocusedConfiguration(b)) => a === b
    case _                                    => false
  }

  val obsId: Optional[Focused, ExploreObservation.Id]                                 =
    GenPrism[Focused, FocusedObs].composeLens(FocusedObs.obsId)
  val targetId: Optional[Focused, SiderealTarget.Id]                                  =
    GenPrism[Focused, FocusedTarget].composeLens(FocusedTarget.targetId)
  val targetOrObsId: Prism[Focused, Either[SiderealTarget.Id, ExploreObservation.Id]] =
    Prism[Focused, Either[SiderealTarget.Id, ExploreObservation.Id]] {
      case FocusedObs(obsId)       => obsId.asRight.some
      case FocusedTarget(targetId) => targetId.asLeft.some
      case _                       => none
    } {
      _ match {
        case Right(obsId)   => FocusedObs(obsId)
        case Left(targetId) => FocusedTarget(targetId)
      }
    }

}
