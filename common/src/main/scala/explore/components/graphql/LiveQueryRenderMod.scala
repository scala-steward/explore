// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package explore.components.graphql

import scala.concurrent.duration._
import scala.language.postfixOps

import cats.data.NonEmptyList
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.IO
import cats.effect.Timer
import cats.syntax.all._
import clue.GraphQLStreamingClient
import clue.StreamingClientStatus
import crystal.Pot
import crystal.ViewF
import crystal.react._
import crystal.react.implicits._
import explore._
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.Logger
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import react.common._
import react.semanticui.collections.message.Message
import react.semanticui.elements.icon.Icon
import react.semanticui.sizes._

final case class LiveQueryRenderMod[S, D, A](
  query:               IO[D],
  extract:             D => A,
  changeSubscriptions: NonEmptyList[IO[GraphQLStreamingClient[IO, S]#Subscription[_]]]
)(
  val valueRender:     View[A] => VdomNode,
  val pendingRender:   Long => VdomNode = (_ => Icon(name = "spinner", loading = true, size = Large)),
  val errorRender:     Throwable => VdomNode = (t => Message(error = true)(t.getMessage)),
  val onNewData:       IO[Unit] = IO.unit
)(implicit
  val ce:              ConcurrentEffect[IO],
  val timer:           Timer[IO],
  val cs:              ContextShift[IO],
  val logger:          Logger[IO],
  val reuse:           Reusability[A],
  val client:          GraphQLStreamingClient[IO, S]
) extends ReactProps(LiveQueryRenderMod.component)
    with LiveQueryRenderMod.Props[IO, S, D, A]

object LiveQueryRenderMod {
  trait Props[F[_], S, D, A] {
    val query: F[D]
    val extract: D => A
    val changeSubscriptions: NonEmptyList[F[GraphQLStreamingClient[F, S]#Subscription[_]]]

    val valueRender: ViewF[F, A] => VdomNode
    val pendingRender: Long => VdomNode
    val errorRender: Throwable => VdomNode
    val onNewData: F[Unit]

    implicit val ce: ConcurrentEffect[F]
    implicit val timer: Timer[F]
    implicit val cs: ContextShift[F]
    implicit val logger: Logger[F]
    implicit val reuse: Reusability[A]
    implicit val client: GraphQLStreamingClient[F, S]
  }

  final case class State[F[_], S, D, A](
    queue:         Queue[F, A],
    subscriptions: NonEmptyList[GraphQLStreamingClient[F, _]#Subscription[_]],
    renderer:      StreamRendererMod.Component[F, A]
  )

  // Reusability should be controlled by enclosing components and reuse parameter. We allow rerender every time it's requested.
  implicit def propsReuse[F[_], S, D, A]: Reusability[Props[F, S, D, A]]                 = Reusability.never
  implicit def stateReuse[F[_], S, D, A]: Reusability[State[F, S, D, A]]                 = Reusability.never
  implicit protected def renderReuse[F[_], A]: Reusability[Pot[ViewF[F, A]] => VdomNode] =
    Reusability.never

  protected def componentBuilder[F[_], S, D, A] =
    ScalaComponent
      .builder[Props[F, S, D, A]]
      .initialState[Option[State[F, S, D, A]]](none)
      .render { $ =>
        React.Fragment(
          $.state.fold[VdomNode](EmptyVdom)(
            _.renderer(_.fold($.props.pendingRender, $.props.errorRender, $.props.valueRender))
          )
        )
      }
      .componentDidMount { $ =>
        implicit val ce     = $.props.ce
        implicit val timer  = $.props.timer
        implicit val cs     = $.props.cs
        implicit val logger = $.props.logger
        implicit val reuse  = $.props.reuse

        def queryAndEnqueue(queue: Queue[F, A]): F[Unit] =
          for {
            result <- $.props.query.map($.props.extract)
            _      <- queue.enqueue1(result)
            _      <- $.props.onNewData
          } yield ()

        def trackChanges(
          subscriptions: NonEmptyList[GraphQLStreamingClient[F, S]#Subscription[_]],
          queue:         Queue[F, A]
        ): F[Unit] =
          subscriptions
            .map(_.stream)
            .reduceLeft(_ merge _)
            .evalMap(_ => queryAndEnqueue(queue))
            .compile
            .drain

        def trackConnection(queue: Queue[F, A]): F[Unit] =
          $.props.client.statusStream
            .filter(_ == StreamingClientStatus.Open)
            .evalMap(_ => queryAndEnqueue(queue))
            .compile
            .drain

        val init =
          for {
            queue         <- Queue.unbounded[F, A]
            subscriptions <- $.props.changeSubscriptions.sequence
            renderer       = StreamRendererMod.build(queue.dequeue, holdAfterMod = (2 seconds).some)
            _             <- $.setStateIn[F](State(queue, subscriptions, renderer).some)
            _             <- queryAndEnqueue(queue)
            _             <- trackChanges(subscriptions, queue).handleErrorWith(t =>
                               logger.error(t)("Error updating LiveQueryRenderMod")
                             )
            _             <- trackConnection(queue)
          } yield ()

        init
          .handleErrorWith(t => logger.error(t)("Error initializing LiveQueryRenderMod"))
          .runInCB
      }
      .componentWillUnmount { $ =>
        implicit val ce = $.props.ce

        $.state.map(_.subscriptions.map(_.stop()).sequence.void.runInCB).getOrEmpty
      }
      .configure(Reusability.shouldComponentUpdate)
      .build

  val component = componentBuilder[IO, Any, Any, Any]
}