// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package explore.model

import cats.kernel.laws.discipline.EqTests
import explore.model.arb.all._
import munit.DisciplineSuite

class ObsSummarySuite extends DisciplineSuite {
  checkAll("Eq[ObsSummaryWithConstraints]", EqTests[ObsSummaryWithConstraints].eqv)
  checkAll("Eq[ObsSummaryWithPointingAndConstraints]",
           EqTests[ObsSummaryWithPointingAndConstraints].eqv
  )
}
