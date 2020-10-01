// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package explore.target

import java.util.UUID

import cats.effect.IO
import cats.syntax.all._
import clue.GraphQLQuery
import eu.timepit.refined.types.string.NonEmptyString
import explore.implicits._
import explore.model.SiderealTarget
import explore.model.decoders._
import explore.undo.Undoer
import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.refined._
import monocle.Lens
import monocle.macros.Lenses

object TargetQueries {

  object Subscription extends GraphQLQuery {
    val document = """
      subscription ($id: uuid!) {
        targets(where: {id: {_eq: $id}}) {
          id
          name
          object_type
          ra
          dec
        }
      }
      """

    case class Variables(id: UUID)
    object Variables { implicit val jsonEncoder: Encoder[Variables] = deriveEncoder[Variables] }

    @Lenses
    case class Data(targets: List[SiderealTarget])
    object Data { implicit val jsonDecoder: Decoder[Data] = deriveDecoder[Data] }

    implicit val varEncoder: Encoder[Variables] = Variables.jsonEncoder
    implicit val dataDecoder: Decoder[Data]     = Data.jsonDecoder
  }

  object Mutation extends GraphQLQuery {
    val document = """
      mutation ($id: uuid, $fields: targets_set_input){
        update_targets(_set: $fields, where: {
          id: {
            _eq: $id
          }
        }) {
          affected_rows
        }
      }
    """

    case class Fields(
      name: Option[NonEmptyString] = none,
      ra:   Option[String] = none,
      dec:  Option[String] = none
    )
    object Fields {
      implicit val jsonEncoder: Encoder[Fields] = deriveEncoder[Fields].mapJson(_.dropNullValues)
    }

    case class Variables(id: SiderealTarget.Id, fields: Fields)
    object Variables { implicit val jsonEncoder: Encoder[Variables] = deriveEncoder[Variables] }

    case class Data(update_targets: JsonObject) // We are ignoring affected_rows
    object Data { implicit val jsonDecoder: Decoder[Data] = deriveDecoder[Data] }

    implicit val varEncoder: Encoder[Variables] = Variables.jsonEncoder
    implicit val dataDecoder: Decoder[Data]     = Data.jsonDecoder
  }

  private def mutate(id: SiderealTarget.Id, fields: Mutation.Fields)(implicit
    ctx:                 AppContextIO
  ): IO[Unit] =
    ctx.clients.odb
      .query(Mutation)(Mutation.Variables(id, fields).some)
      .void

  case class UndoSet(
    id:           SiderealTarget.Id,
    view:         View[SiderealTarget],
    setter:       Undoer.Setter[IO, SiderealTarget]
  )(implicit ctx: AppContextIO) {
    def apply[A](
      lens:   Lens[SiderealTarget, A],
      fields: A => Mutation.Fields
    )(
      value:  A
    ): IO[Unit] =
      setter.set(
        view.get,
        lens.get,
        { value: A =>
          for {
            _ <- (view.mod).compose(lens.set)(value)
            _ <- mutate(id, fields(value))
          } yield ()
        }
      )(value)
  }

}
