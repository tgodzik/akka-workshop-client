package client

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorIdentity, ActorRef, ActorSystem, AllForOneStrategy, Identify, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import com.virtuslab.akkaworkshop.PasswordsDistributor._
import akka.pattern.ask
import akka.routing.{RoundRobinGroup, RoundRobinPool}
import akka.util.Timeout
import client.RequesterActor.RegisterSupervisor
import com.virtuslab.akkaworkshop.{Decrypter, PasswordDecoded, PasswordPrepared}

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.{Success, Try}

class RequesterActor(remoteServer : ActorRef) extends Actor {

  import RequesterActor._

  var token : Token = ""
  var supervisorSet = Set.empty[(ActorRef, Int)]
  var router: ActorRef = _
  override def preStart() = {
    remoteServer !  registerMessage("Fifi")
  }

  def createRouter = {
    if(router != null)
      router ! PoisonPill

     context.actorOf(RoundRobinGroup(supervisorSet.map(_._1.path.toString)).props(), "router" + supervisorSet.size)
  }
  // receive with messages that can be sent by the server
  override def receive: Receive = {

    case Registered(tkn) =>
      println(s"Registered with token $tkn")
      token = tkn
      context.become(registered)
      println(supervisorSet.map(_._1.path.toString))
      router = createRouter

      for{
        (supRef, num) <- supervisorSet
        _ <- 0 until num
      } remoteServer ! sendPasswordMessage(token)

    case RegisterSupervisor(ref, num) =>
      println(supervisorSet.map(_._1.path.toString))
      supervisorSet = supervisorSet + (ref -> num)

  }

   def registered: Receive = {

     case RegisterSupervisor(ref, num) =>
       supervisorSet = supervisorSet + (ref -> num)
       println(supervisorSet.map(_._1.path.toString))
       for (_ <- 0 until num) remoteServer ! sendPasswordMessage(token)
       router = createRouter

    case ev@EncryptedPassword(encryptedPassword) =>
      router ! ev

    case ValidateDecodedPassword(_, enc, dec) =>
      remoteServer ! validatePasswordMessage(token, enc, dec)

    case PasswordCorrect(decryptedPassword) =>
      println("Was correct")
      remoteServer ! sendPasswordMessage(token)


    case PasswordIncorrect(decryptedPassword) =>
      println("Was incorrect")
      remoteServer ! sendPasswordMessage(token)


  }

}

object RequesterActor {

  def props(actorRef: ActorRef) = Props(classOf[RequesterActor], actorRef)

  // messages needed to communicate with the server

  def registerMessage(name : String) = Register(name)

  def validatePasswordMessage(token: Token,
                              encryptedPassword : String,
                              decryptedPassword : String) =
    ValidateDecodedPassword(token, encryptedPassword, decryptedPassword)

  def sendPasswordMessage(token: Token) = SendMeEncryptedPassword(token)

  case class RegisterSupervisor(actorRef: ActorRef, workerNum : Int)
}

class Supervisor(requester : ActorRef) extends Actor {

  val workersNum = 10
  val restartingStrategy = AllForOneStrategy() { case _: Exception => Restart }
  val workers = context.actorOf(RoundRobinPool(workersNum, supervisorStrategy = restartingStrategy).props(Worker.props))
  var workersAvailable = workersNum

  override def preStart(): Unit = {
    requester ! RegisterSupervisor(self, workersNum)
  }

  override def receive: Receive = {
    case ev@EncryptedPassword(encryptedPassword) =>
      println("gets paswords")
      workers forward List(ev.encryptedPassword)
  }
}

class Worker extends Actor{
  var decrypter = new Decrypter

  override def preRestart(reason: Throwable, message: Option[Any]) {
    message foreach { self.forward }
  }

  override def receive: Actor.Receive = waitingForNewPassword

  def waitingForNewPassword: Actor.Receive = {
    case (encryptedPassword: String) :: Nil =>
      self forward (decrypter.prepare(encryptedPassword) :: List(encryptedPassword))
      context.become(processing)
    case msg :: history =>
      self forward history
      if (history.length > 1) context.become(processing)
  }

  def processing: Actor.Receive = {
    case (preparedPassword: PasswordPrepared) :: hs =>
      self forward (decrypter.decode(preparedPassword) :: preparedPassword :: hs)
    case (decodedPassword: PasswordDecoded) :: hs =>
      self forward (decrypter.decrypt(decodedPassword) :: decodedPassword :: hs)
    case  (decryptedPassword: String) :: decodedPassword :: preparedPassword :: (encryptedPassword: String) :: Nil =>
      sender ! ValidateDecodedPassword("", encryptedPassword, decryptedPassword)
      context.become(waitingForNewPassword)
  }
}

object Worker {

  def props = Props[Worker]

}

object AkkaApplication extends App {

  implicit val timeout = Timeout(10.seconds)

  //actor system
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
