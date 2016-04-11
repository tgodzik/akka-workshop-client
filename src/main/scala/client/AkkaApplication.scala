package client

import akka.actor.{ActorIdentity, ActorSystem, Identify, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Success, Try}

object AkkaApplication extends App {

  implicit val timeout = Timeout(10.seconds)

  val system = ActorSystem("ClusterSystem")

  val remoteServer = system.actorSelection("akka.tcp://application@127.0.0.1:9552/user/PasswordsDistributor")

  val remoteServerRef = Try(Await.result((remoteServer ? Identify("123L")).mapTo[ActorIdentity], 10.seconds).ref)

  remoteServerRef match {
    case Success(Some(ref)) =>

      system.actorOf(ClusterSingletonManager.props(
        singletonProps = RequesterActor.props(ref),
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(system)),
        name = "consumer"
      )

      val singleton = system.actorOf(ClusterSingletonProxy.props(
        singletonManagerPath = "/user/consumer",
        settings = ClusterSingletonProxySettings(system)),
        name = "consumerProxy"
      )
      system.actorOf(Props(classOf[Supervisor], singleton))

    case _ =>
      system.terminate()
  }




}
