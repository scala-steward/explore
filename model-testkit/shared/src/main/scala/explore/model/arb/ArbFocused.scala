// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package explore.model.arb

import cats.syntax.all._
import explore.model.Focused
import explore.model.Focused.FocusedAsterism
import explore.model.Focused.FocusedObs
import explore.model.Focused.FocusedTarget
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary._
import org.scalacheck.Cogen
import org.scalacheck.Cogen._
import org.scalacheck.Gen
import org.scalacheck.Gen._
import lucuma.core.model.Asterism
import lucuma.core.model.Observation
import lucuma.core.model.Target
import lucuma.core.util.arb.ArbGid._

trait ArbFocused {
  implicit val focusedArb: Arbitrary[Focused] =
    Arbitrary(
      oneOf(focusedObsGen, focusedTargetGen, focusedAsterismGen)
    )

  val focusedObsGen: Gen[Focused.FocusedObs] =
    arbitrary[Observation.Id].map(FocusedObs.apply)

  val focusedTargetGen: Gen[Focused.FocusedTarget] =
    arbitrary[Target.Id].map(FocusedTarget.apply)

  val focusedAsterismGen: Gen[Focused.FocusedAsterism] =
    arbitrary[Asterism.Id].map(FocusedAsterism.apply)

  implicit val focusedObsCogen: Cogen[Focused.FocusedObs] =
    Cogen[Observation.Id].contramap(_.obsId)

  implicit val focusedTargetCogen: Cogen[Focused.FocusedTarget] =
    Cogen[Target.Id].contramap(_.targetId)

  implicit val focusedAsterismCogen: Cogen[Focused.FocusedAsterism] =
    Cogen[Asterism.Id].contramap(_.asterismId)

  implicit val focusedCogen: Cogen[Focused] =
    Cogen[Either[Either[FocusedObs, FocusedTarget], FocusedAsterism]]
      .contramap {
        case a: Focused.FocusedObs      => a.asLeft.asLeft
        case a: Focused.FocusedTarget   => a.asRight.asLeft
        case a: Focused.FocusedAsterism => a.asRight
      }
}

object ArbFocused extends ArbFocused
