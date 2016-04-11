package client

import akka.actor.{Actor, ActorRef, Props}
import akka.routing.RoundRobinGroup
import com.virtuslab.akkaworkshop.PasswordsDistributor._


class RequesterActor(remoteServer : ActorRef) extends Actor {

  import RequesterActor._

  var token : Token = ""
  var supervisorSet = Set.empty[(ActorRef, Int)]

  val name = "Basic"

  override def preStart() = {
    remoteServer !  registerMessage(name)
  }

  def createRouter = {
    val addresses = supervisorSet.map(_._1.path.toString)
    context.actorOf(RoundRobinGroup(addresses).props(), s"router_${supervisorSet.size}")
  }

  override def receive: Receive = starting

  def starting : Receive = {

    case Registered(newToken) =>
      println(s"Registered with token $newToken")
      token = newToken

      val router = createRouter
      context.become(registered(router))

      for {
        (supRef, num) <- supervisorSet
        _ <- 0 until num
      } remoteServer ! sendPasswordMessage(token)

    case RegisterSupervisor(ref, num) =>
      supervisorSet = supervisorSet + (ref -> num)

  }

  def registered(router : ActorRef): Receive = {

    case RegisterSupervisor(ref, num) =>
      supervisorSet = supervisorSet + (ref -> num)
      for (_ <- 0 until num) remoteServer ! sendPasswordMessage(token)

      context.stop(router)
      val newRouter = createRouter
      context.become(registered(newRouter))

    case ev@EncryptedPassword(encryptedPassword) =>
      router ! ev

    case ValidateDecodedPassword(_, enc, dec) =>
      remoteServer ! validatePasswordMessage(token, enc, dec)

    case PasswordCorrect(decryptedPassword) =>
      println("Password was decrypted correctly.")
      remoteServer ! sendPasswordMessage(token)


    case PasswordIncorrect(decryptedPassword) =>
      println("ERROR: Password was decrypted incorrect")
      remoteServer ! sendPasswordMessage(token)


  }

}


object RequesterActor {

  def props(remoteServer: ActorRef) = Props(classOf[RequesterActor], remoteServer)

  // messages needed to communicate with the server
  def registerMessage(name : String) = Register(name)

  def validatePasswordMessage(token: Token,
                              encryptedPassword : String,
                              decryptedPassword : String) =
    ValidateDecodedPassword(token, encryptedPassword, decryptedPassword)

  def sendPasswordMessage(token: Token) = SendMeEncryptedPassword(token)

  case class RegisterSupervisor(actorRef: ActorRef, workerNum : Int)
}
