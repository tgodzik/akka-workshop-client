package client

import akka.actor.{ActorIdentity, ActorSystem, Identify}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Success, Try}


object AkkaApplication extends App {

  implicit val timeout = Timeout(10.seconds)

  val system = ActorSystem("RequesterSystem")

  val remoteServer = system.actorSelection("akka.tcp://application@headquarters:9552/user/PasswordsDistributor")

  val remoteServerRef = Try(Await.result((remoteServer ? Identify("123L")).mapTo[ActorIdentity], 10.seconds).ref)

  remoteServerRef match {
    case Success(Some(ref)) =>
      system.actorOf(RequesterActor.props(ref), name = "requester")

    case _ =>
      system.terminate()
  }


}
