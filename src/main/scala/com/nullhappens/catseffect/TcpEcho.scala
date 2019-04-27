package com.nullhappens.catseffect

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, PrintWriter}
import java.net.{ServerSocket, Socket}

import cats.effect._
import cats.effect.syntax.all._
import cats.effect.ExitCase._
import cats.effect.concurrent.MVar
import cats.implicits._

object TcpEcho extends IOApp {
  def echoProtocol[F[_]: Sync](clientSocket: Socket,
                               stopFlag: MVar[F, Unit]): F[Unit] = {

    def loop(reader: BufferedReader, writer: BufferedWriter): F[Unit] =
      for {
        line <- Sync[F].delay(reader.readLine())
        _ <- line match {
          case "STOP" => stopFlag.put(())
          case ""     => Sync[F].unit
          case _ =>
            Sync[F].delay {
              writer.write(line)
              writer.newLine()
              writer.flush()
            } >> loop(reader, writer)
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
        loop(reader, writer)
    }
  }

  def serve[F[_]: Concurrent](serverSocket: ServerSocket,
                              stopFlag: MVar[F, Unit]): F[Unit] = {
    def close(socket: Socket): F[Unit] =
      Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)

    for {
      _ <- Sync[F]
        .delay(serverSocket.accept())
        .bracketCase { socket =>
          echoProtocol(socket, stopFlag)
            .guarantee(close(socket))
            .start
        } { (socket, exit) =>
          exit match {
            case Completed           => Sync[F].unit
            case Error(_) | Canceled => close(socket)
          }
        }
      _ <- serve(serverSocket, stopFlag)
    } yield ()
  }

  def server[F[_]: Concurrent](serverSocket: ServerSocket): F[ExitCode] =
    for {
      stopFlag <- MVar[F].empty[Unit]
      serverFiber <- serve(serverSocket, stopFlag).start
      _ <- stopFlag.read
      _ <- serverFiber.cancel
    } yield ExitCode.Success

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
