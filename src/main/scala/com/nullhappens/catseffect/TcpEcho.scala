package com.nullhappens.catseffect

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, PrintWriter}
import java.net.{ServerSocket, Socket}
import java.util.concurrent.Executors

import cats.effect.ExitCase._
import cats.effect._
import cats.effect.concurrent.MVar
import cats.effect.syntax.all._
import cats.implicits._

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.util.Try

object TcpEcho extends IOApp {

  def echoProtocol[F[_]: Async](clientSocket: Socket, stopFlag: MVar[F, Unit])(
      implicit clientExecutionContext: ExecutionContext): F[Unit] = {

//    val csf = implicitly[ContextShift[F]]

    def loop(reader: BufferedReader,
             writer: BufferedWriter,
             stopFlag: MVar[F, Unit]): F[Unit] =
      for {
//        lineE <- csf.evalOn(clientExecutionContext)(
//          Sync[F].delay(reader.readLine()).attempt)
        lineE <- Async[F].async {
          (cb: Either[Throwable, Either[Throwable, String]] => Unit) =>
            clientExecutionContext.execute(new Runnable {
              override def run(): Unit = {
                val result: Either[Throwable, String] =
                  Try(reader.readLine()).toEither
                cb(Right(result))
              }
            })
        }
        _ <- lineE match {
          case Right(line) =>
            line match {
              case "STOP" => stopFlag.put(())
              case ""     => Sync[F].unit
              case _ =>
                Sync[F].delay {
                  writer.write(
                    s"[${Thread.currentThread().getName}-${Thread.currentThread().getId}] $line")
                  writer.newLine()
                  writer.flush()
                } >> loop(reader, writer, stopFlag)
            }
          case Left(e) =>
            for {
              isEmpty <- stopFlag.isEmpty
              _ <- if (!isEmpty) Sync[F].unit else Sync[F].raiseError(e)
            } yield ()
        }
      } yield ()

    def reader(clientSocket: Socket): Resource[F, BufferedReader] =
      Resource.make {
        Sync[F].delay(
          new BufferedReader(
            new InputStreamReader(clientSocket.getInputStream)))
      } { reader =>
        Sync[F].delay(reader.close()).handleErrorWith(_ => Sync[F].unit)
      }

    def writer(clientSocket: Socket): Resource[F, BufferedWriter] =
      Resource.make {
        Sync[F].delay(
          new BufferedWriter(new PrintWriter(clientSocket.getOutputStream)))
      } { writer =>
        Sync[F].delay(writer.close()).handleErrorWith(_ => Sync[F].unit)
      }

    def readerWriter(
        clientSocket: Socket): Resource[F, (BufferedReader, BufferedWriter)] =
      for {
        reader <- reader(clientSocket)
        writer <- writer(clientSocket)
      } yield (reader, writer)

    readerWriter(clientSocket).use {
      case (reader, writer) =>
        loop(reader, writer, stopFlag)
    }
  }

  def serve[F[_]: Concurrent: ContextShift](serverSocket: ServerSocket,
                                            stopFlag: MVar[F, Unit])(
      implicit clientsExecutionContext: ExecutionContext): F[Unit] = {
    def close(socket: Socket): F[Unit] =
      Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)

    for {
      socket <- Sync[F]
        .delay(serverSocket.accept())
        .bracketCase { socket =>
          echoProtocol(socket, stopFlag)
            .guarantee(close(socket))
            .start >> Sync[F].pure(socket)
        } { (socket, exit) =>
          exit match {
            case Completed           => Sync[F].unit
            case Error(_) | Canceled => close(socket)
          }
        }
      _ <- (stopFlag.read >> close(socket)).start
      _ <- serve(serverSocket, stopFlag)
    } yield ()
  }

  def server[F[_]: Concurrent: ContextShift](
      serverSocket: ServerSocket): F[ExitCode] = {

    val clientsThreadPool = Executors.newCachedThreadPool()
    implicit val clientsExecutionContext: ExecutionContextExecutor =
      ExecutionContext.fromExecutor(clientsThreadPool)

    for {
      stopFlag <- MVar[F].empty[Unit]
      serverFiber <- serve(serverSocket, stopFlag).start
      _ <- stopFlag.read
      _ <- Sync[F].delay(clientsThreadPool.shutdown())
      _ <- serverFiber.cancel
    } yield ExitCode.Success
  }

  override def run(args: List[String]): IO[ExitCode] = {
    def close[F[_]: Sync](socket: ServerSocket): F[Unit] =
      Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)

    IO(new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)))
      .bracket { serverSocket =>
        server[IO](serverSocket) >> IO.pure(ExitCode.Success)
      } { serverSocket =>
        close[IO](serverSocket) >> IO(println("Server finished"))
      }
  }

}
