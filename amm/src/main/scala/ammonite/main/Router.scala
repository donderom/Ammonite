package ammonite.main

import sourcecode.Compat.Context

import scala.annotation.StaticAnnotation
import scala.collection.mutable
import scala.language.experimental.macros
/**
  * More or less a minimal version of Autowire's Server that lets you generate
  * a set of "routes" from the methods defined in an object, and call them
  * using passing in name/args/kwargs via Java reflection, without having to
  * generate/compile code or use Scala reflection. This saves us spinning up
  * the Scala compiler and greatly reduces the startup time of cached scripts.
  */
object Router{
  class doc(s: String) extends StaticAnnotation
  class main extends StaticAnnotation
  def generateRoutes[T](t: T): Seq[Router.EntryPoint] = macro generateRoutesImpl[T]
  def generateRoutesImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T]): c.Expr[Seq[EntryPoint]] = {
    import c.universe._
    val r = new Router(c)
    val allRoutes = r.getAllRoutesForClass(
      weakTypeOf[T].asInstanceOf[r.c.Type],
      t.tree.asInstanceOf[r.c.Tree]
    ).asInstanceOf[Iterable[c.Tree]]

    c.Expr[Seq[EntryPoint]](q"_root_.scala.Seq(..$allRoutes)")
  }

  /**
    * Models what is known by the router about a single argument: that it has
    * a [[name]], a human-readable [[typeString]] describing what the type is
    * (just for logging and reading, not a replacement for a `TypeTag`) and
    * possible a function that can compute its default value
    */
  case class ArgSig(name: String,
                    typeString: String,
                    doc: Option[String],
                    default: Option[() => Any])

  /**
    * What is known about a single endpoint for our routes. It has a [[name]],
    * [[argSignatures]] for each argument, and a macro-generated [[invoke0]]
    * that performs all the necessary argument parsing and de-serialization.
    *
    * Realistically, you will probably spend most of your time calling [[invoke]]
    * instead, which provides a nicer API to call it that mimmicks the API of
    * calling a Scala method.
    */
  case class EntryPoint(name: String,
                        argSignatures: Seq[ArgSig],
                        varargs: Boolean,
                        invoke0: (Map[String, String], Seq[String]) => Result[Any]){
    def invoke(groupedArgs: Seq[(String, Option[String])]): Result[Any] = {
      var remainingArgSignatures = argSignatures.toList


      val accumulatedKeywords = mutable.Map.empty[ArgSig, mutable.Buffer[String]]
      val keywordableArgs = if (varargs) argSignatures.dropRight(1) else argSignatures

      for(arg <- keywordableArgs) accumulatedKeywords(arg) = mutable.Buffer.empty

      val leftoverArgs = mutable.Buffer.empty[String]

      val lookupArgSig = argSignatures.map(x => (x.name, x)).toMap

      for(group <- groupedArgs){
        group match{
          case (value, None) =>
            remainingArgSignatures match {
              case Nil => leftoverArgs.append(value)
              case last :: Nil if varargs => leftoverArgs.append(value)
              case next :: rest =>
                accumulatedKeywords(next).append(value)
                remainingArgSignatures = rest
            }
          case (key, Some(value)) =>
            lookupArgSig.get(key) match{
              case Some(x) if accumulatedKeywords.contains(x) =>
                accumulatedKeywords(x).append(value)
                remainingArgSignatures = remainingArgSignatures.filter(_.name != key)
              case _ => leftoverArgs.append("--"+key, value)
            }
        }
      }

      val missing0 = remainingArgSignatures.filter(_.default.isEmpty)
      val missing = if(varargs) missing0.filter(_ != argSignatures.last) else missing0
      val duplicates = accumulatedKeywords.toSeq.filter(_._2.length > 1)

      if (missing.nonEmpty || duplicates.nonEmpty || (leftoverArgs.nonEmpty && !varargs)){
        Result.Error.MismatchedArguments(
          missing = missing,
          unknown = leftoverArgs,
          duplicate = duplicates

        )
      } else {
        val mapping = accumulatedKeywords
          .iterator
          .collect{case (k, Seq(single)) => (k.name, single)}
          .toMap

        try invoke0(mapping, leftoverArgs)
        catch{case e: Throwable =>
          Result.Error.Exception(e)
        }
      }
    }
  }

  def tryEither[T](t: => T, error: Throwable => Result.ParamError) = {
    try Right(t)
    catch{ case e: Throwable => Left(error(e))}
  }
  def readVarargs[T](arg: ArgSig,
                     values: Seq[String],
                     thunk: String => T) = {
    val attempts =
      for(item <- values)
      yield tryEither(thunk(item), Result.ParamError.Invalid(arg, item, _))


    val bad = attempts.collect{ case Left(x) => x}
    if (bad.nonEmpty) Left(bad)
    else Right(attempts.collect{case Right(x) => x})
  }
  def read[T](dict: Map[String, String],
              default: => Option[Any],
              arg: ArgSig,
              thunk: String => T): FailMaybe = {
    dict.get(arg.name) match{
      case None =>
        tryEither(default.get, Result.ParamError.DefaultFailed(arg, _)).left.map(Seq(_))

      case Some(x) =>
        tryEither(thunk(x), Result.ParamError.Invalid(arg, x, _)).left.map(Seq(_))
    }
  }

  /**
    * Represents what comes out of an attempt to invoke an [[EntryPoint]].
    * Could succeed with a value, but could fail in many different ways.
    */
  sealed trait Result[+T]
  object Result{

    /**
      * Invoking the [[EntryPoint]] was totally successful, and returned a
      * result
      */
    case class Success[T](value: T) extends Result[T]

    /**
      * Invoking the [[EntryPoint]] was not successful
      */
    sealed trait Error extends Result[Nothing]
    object Error{

      /**
        * Invoking the [[EntryPoint]] failed with an exception while executing
        * code within it.
        */
      case class Exception(t: Throwable) extends Error

      /**
        * Invoking the [[EntryPoint]] failed because the arguments provided
        * did not line up with the arguments expected
        */
      case class MismatchedArguments(missing: Seq[ArgSig],
                                    unknown: Seq[String],
                                    duplicate: Seq[(ArgSig, Seq[String])]) extends Error
      /**
        * Invoking the [[EntryPoint]] failed because there were problems
        * deserializing/parsing individual arguments
        */
      case class InvalidArguments(values: Seq[ParamError]) extends Error
    }

    sealed trait ParamError
    object ParamError{
      /**
        * Something went wrong trying to de-serialize the input parameter;
        * the thrown exception is stored in [[ex]]
        */
      case class Invalid(arg: ArgSig, value: String, ex: Throwable) extends ParamError
      /**
        * Something went wrong trying to evaluate the default value
        * for this input parameter
        */
      case class DefaultFailed(arg: ArgSig, ex: Throwable) extends ParamError
    }
  }


  type FailMaybe = Either[Seq[Result.ParamError], Any]
  type FailAll = Either[Seq[Result.ParamError], Seq[Any]]

  def validate(args: Seq[FailMaybe]): Result[Seq[Any]] = {
    val lefts = args.collect{case Left(x) => x}.flatten

    if (lefts.nonEmpty) Result.Error.InvalidArguments(lefts)
    else {
      val rights = args.collect{case Right(x) => x}
      Result.Success(rights)
    }
  }
}
class Router [C <: Context](val c: C) {
  import c.universe._
  def getValsOrMeths(curCls: Type): Iterable[MethodSymbol] = {
    def isAMemberOfAnyRef(member: Symbol) =
      weakTypeOf[AnyRef].members.exists(_.name == member.name)
    val extractableMembers = for {
      member <- curCls.declarations
      if !isAMemberOfAnyRef(member)
      if !member.isSynthetic
      if member.isPublic
      if member.isTerm
      memTerm = member.asTerm
      if memTerm.isMethod
    } yield memTerm.asMethod
    extractableMembers flatMap { case memTerm =>
      if (memTerm.isSetter || memTerm.isConstructor || memTerm.isGetter) Nil
      else Seq(memTerm)

    }
  }

  def extractMethod(meth: MethodSymbol,
                    curCls: c.universe.Type,
                    target: c.Tree): c.universe.Tree = {
    val flattenedArgLists = meth.paramss.flatten
    def hasDefault(i: Int) = {
      val defaultName = s"${meth.name}$$default$$${i + 1}"
      if (curCls.members.exists(_.name.toString == defaultName)) {
        Some(defaultName)
      } else {
        None
      }
    }
    val argListSymbol = q"${c.fresh[TermName]("argsList")}"
    val extrasSymbol = q"${c.fresh[TermName]("extras")}"
    val defaults = for ((arg, i) <- flattenedArgLists.zipWithIndex) yield {
      hasDefault(i).map(defaultName => q"() => $target.${newTermName(defaultName)}")
    }

    def unwrapVarargType(arg: Symbol) = {
      val vararg = arg.typeSignature.typeSymbol == definitions.RepeatedParamClass
      val unwrappedType =
        if (!vararg) arg.typeSignature
        else arg.typeSignature.asInstanceOf[TypeRef].args(0)

      (vararg, unwrappedType)
    }
    val readArgSigs = for(
      ((arg, defaultOpt), i) <- flattenedArgLists.zip(defaults).zipWithIndex
    ) yield {

      val (vararg, unwrappedType) = unwrapVarargType(arg)

      val default =
        if (vararg) q"scala.Some(scala.Nil)"
        else defaultOpt match {
          case Some(defaultExpr) => q"scala.Some($defaultExpr())"
          case None => q"scala.None"
        }

      val docs = for{
        doc <- arg.annotations.find(_.tpe =:= typeOf[Router.doc])
        if doc.scalaArgs.head.isInstanceOf[Literal]
        l =  doc.scalaArgs.head.asInstanceOf[Literal]
        if l.value.value.isInstanceOf[String]
      } yield l.value.value.asInstanceOf[String]

      val docTree = docs match{
        case None => q"scala.None"
        case Some(s) => q"scala.Some($s)"
      }
      val argSig = q"""
        ammonite.main.Router.ArgSig(
          ${arg.name.toString},
          ${arg.typeSignature.toString},
          $docTree,
          $defaultOpt
        )
      """

      val reader =
        if(vararg) q"""
          ammonite.main.Router.readVarargs[$unwrappedType](
            $argSig,
            $extrasSymbol,
            implicitly[scopt.Read[$unwrappedType]].reads(_)
          )
        """ else q"""
        ammonite.main.Router.read[$unwrappedType](
          $argListSymbol,
          $default,
          $argSig,
          implicitly[scopt.Read[$unwrappedType]].reads(_)
        )
        """
      (reader, argSig, vararg)
    }

    val (readArgs, argSigs, varargs) = readArgSigs.unzip3
    val (argNames, argNameCasts) = flattenedArgLists.map { arg =>
      val (vararg, unwrappedType) = unwrapVarargType(arg)
      (
        pq"${arg.name.toTermName}",
        if (!vararg) q"${arg.name.toTermName}.asInstanceOf[$unwrappedType]"
        else q"${arg.name.toTermName}.asInstanceOf[Seq[$unwrappedType]]: _*"

        )
    }.unzip

    q"""
    ammonite.main.Router.EntryPoint(
      ${meth.name.toString},
      scala.Seq(..$argSigs),
      ${varargs.contains(true)},
      ($argListSymbol: Map[String, String], $extrasSymbol: Seq[String]) =>
        ammonite.main.Router.validate(Seq(..$readArgs)) match{
          case ammonite.main.Router.Result.Success(List(..$argNames)) =>
            ammonite.main.Router.Result.Success($target.${meth.name.toTermName}(..$argNameCasts))
          case x => x
        }
    )
    """
  }

  def getAllRoutesForClass(curCls: Type, target: c.Tree): Iterable[c.universe.Tree] = for{
    t <- getValsOrMeths(curCls)
    if t.annotations.exists(_.tpe =:= typeOf[Router.main])
  } yield extractMethod(t, curCls, target)
}

