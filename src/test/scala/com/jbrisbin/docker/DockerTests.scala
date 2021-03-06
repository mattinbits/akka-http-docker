package com.jbrisbin.docker

import java.nio.charset.Charset

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import org.hamcrest.MatcherAssert._
import org.hamcrest.Matchers._
import org.junit.{After, Test}
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
class DockerTests {

  val log = LoggerFactory.getLogger(classOf[DockerTests])

  val charset = Charset.defaultCharset().toString
  val testLabels = Some(Map("test" -> "true"))

  implicit val timeout: Timeout = Timeout(30 seconds)

  implicit val system = ActorSystem("tests")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  @After
  def cleanup(): Unit = {
    val cs = Await.result(
      Docker().containers(all = true, filters = Map("label" -> Seq("test=true"))),
      timeout.duration
    )
    Await.result(
      Docker().remove(Source.fromIterator(() => cs.iterator), volumes = true, force = true),
      timeout.duration
    )
  }

  @Test
  def canListContainers() = {
    val containers = Await.result(Docker().containers(all = true), timeout.duration)
    log.debug("containers: {}", containers)

    assertThat("Containers exist", containers.nonEmpty)
  }

  @Test
  def canFilterContainerList() = {
    Await.result(
      Docker()
        .create(CreateContainer(
          Image = "alpine",
          Cmd = Some(Seq("/bin/sh")),
          Labels = testLabels
        )), timeout.duration
    )
    val containers = Await.result(
      Docker().containers(all = true, filters = Map("label" -> Seq("test"))),
      timeout.duration
    )
    log.debug("containers: {}", containers)

    assertThat("Containers exist", containers.nonEmpty)
  }

  @Test
  def canCreateContainer() = {
    val container = Await.result(Docker().create(
      CreateContainer(
        Name = "runtest",
        Image = "alpine",
        Cmd = Some(Seq("/bin/sh")),
        Labels = testLabels
      )
    ), timeout.duration)
    log.debug("container: {}", container)

    assertThat("Container was created", container.Id, notNullValue())
  }

  @Test
  def canExecInContainer(): Unit = {
    val docker = Docker()

    // Create and start a container
    val container = Await.result(
      docker
        .create(CreateContainer(
          Name = "runtest",
          Cmd = Some(Seq("/bin/cat")),
          Image = "alpine",
          Labels = testLabels,
          Tty = true
        ))
        .flatMap(c => docker.start(c.Id)),
      timeout.duration
    )
    log.debug("container: {}", container)

    // Interact with container via Source
    val src = docker.exec("runtest", Exec(Seq("ls", "-la", "/bin/busybox")))
      .map {
        case StdOut(bytes) => {
          val str = bytes.decodeString(charset)
          log.debug(str)
          true
        }
        case StdErr(bytes) => {
          val str = bytes.decodeString(charset)
          log.error(str)
          false
        }
      }

    assertThat("Exec completes successfully", Await.result(src.runFold(true)(_ | _), timeout.duration))
    assertThat("Container was stopped", Await.result(docker.stop("runtest"), timeout.duration))
  }

  //  @Ignore
  @Test
  def canStreamOutput(): Unit = {
    val res = Await.result(
      Docker()
        .create(CreateContainer(
          Image = "alpine",
          Tty = true,
          Cmd = Some(Seq("/bin/cat")),
          Labels = testLabels
        ))
        .flatMap(c => Docker().start(c.Id))
        .flatMap(c => c ? Exec(Seq("ls", "-la", "/bin"))),
      timeout.duration
    )
    //log.debug("result: {}", res)

    val count = res match {
      case stdout: ByteString => {
        stdout
          .decodeString(charset)
          .split("\n")
          .filter(s => s.contains("uname"))
          .foldRight(0)((line, count) => count + 1)
      }
    }

    assertThat("uname command was found with ls", count > 0)
  }

  @Test
  def canListImages() = {
    val images = Await.result(Docker().images(), timeout.duration)
    log.debug("images: {}", images)

    assertThat("Images exist", images.nonEmpty)
  }

}
