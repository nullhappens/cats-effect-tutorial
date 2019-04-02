import cats.effect.{IO, Resource}
import java.io.File
import cats.implicits._
import java.io._

object CopyFile extends App {
  
  def copy(origin: File, destination: File): IO[Long] = 
    inputOutputStreams(origin, destination).use { case (in, out) =>
      transfer(in, out)
    }

  def inputStream(f: File): Resource[IO, FileInputStream] =
    Resource.make {
      IO(new FileInputStream(f))                         // build
    } { inStream =>
      IO(inStream.close()).handleErrorWith(_ => IO.unit) // release
    }

  def outputStream(f: File): Resource[IO, FileOutputStream] =
    Resource.make {
      IO(new FileOutputStream(f))                         // build 
    } { outStream =>
      IO(outStream.close()).handleErrorWith(_ => IO.unit) // release
    }

  def inputOutputStreams(in: File, out: File): Resource[IO, (InputStream, OutputStream)] =
    for {
      inStream  <- inputStream(in)
      outStream <- outputStream(out)
    } yield (inStream, outStream)

  def transmit(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): IO[Long] = 
    for {
      amount <- IO(origin.read(buffer, 0, buffer.size))
      count  <- if (amount > -1) IO(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc + amount)
        else IO.pure(acc)
    } yield count

  def transfer(origin: InputStream, destination: OutputStream): IO[Long] = 
    for {
      buffer <- IO(new Array[Byte](1024 * 10))
      total  <- transmit(origin, destination, buffer, 0L)
    } yield total

}
