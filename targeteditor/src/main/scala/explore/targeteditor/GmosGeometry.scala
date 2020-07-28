// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package explore.targeteditor

import cats.data.NonEmptyMap
import cats.implicits._
import gem.enum.GmosNorthFpu
import gem.enum.GmosSouthFpu
import gem.enum.PortDisposition
import gem.geom.GmosOiwfsProbeArm
import gem.geom.GmosScienceAreaGeometry
import gpp.svgdotjs.svgdotjsSvgJs.mod._
import gsp.math.Angle
import gsp.math.Offset
import gsp.math.geom.ShapeExpression
import gsp.math.geom.svg._
import gsp.math.geom.syntax.shapeexpression._
import gsp.math.syntax.int._

/**
  * Test object to produce a gmos geometry. it is for demo purposes only
  */
object GmosGeometry {

  val posAngle: Angle =
    145.deg

  val guideStarOffset: Offset =
    Offset(170543999.µas.p, -24177003.µas.q)

  val offsetPos: Offset =
    Offset(-60.arcsec.p, 60.arcsec.q)

  val fpu: Option[Either[GmosNorthFpu, GmosSouthFpu]] =
    Some(Right(GmosSouthFpu.LongSlit_5_00))

  val port: PortDisposition =
    PortDisposition.Side

  // Shape to display
  def shapes(posAngle: Angle): NonEmptyMap[String, ShapeExpression] =
    NonEmptyMap.of(
      ("probe", GmosOiwfsProbeArm.shapeAt(posAngle, guideStarOffset, offsetPos, fpu, port)),
      ("patrol-field", GmosOiwfsProbeArm.patrolFieldAt(posAngle, offsetPos, fpu, port)),
      ("science-ccd", GmosScienceAreaGeometry.imaging ⟲ posAngle),
      ("science-ccd-offset", GmosScienceAreaGeometry.imaging ↗ offsetPos ⟲ posAngle)
    )

  // Firefox doesn't properly handle very large coordinates, scale by 1000 at least
  val ScaleFactor = 1000

  val pp: SvgPostProcessor = {
    case p: Polygon   => p.addClass("jts-polygon").addClass("jts")
    case g: G         => g.addClass("jts-group").addClass("jts")
    case c: Container => c.addClass("jts")
    case a            => a
  }

}