/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.entity

import java.time.Clock
import java.time.Instant

import scala.Stream
import scala.util.Try

import spray.json._
import whisk.core.database.DocumentUnreadable
import whisk.http.Messages

/**
 * An abstract superclass that encapsulates properties common to all whisk entities (actions, rules, triggers).
 * The class has a private constructor argument and abstract fields so that case classes that extend this base
 * type can use the default spray JSON ser/des. An abstract entity has the following four properties.
 *
 * @param en the name of the entity, this is part of the primary key for the document
 * @param namespace the namespace for the entity as an abstract field
 * @param version the semantic version as an abstract field
 * @param publish true to share the entity and false to keep it private as an abstract field
 * @param annotation the set of annotations to attribute to the entity
 *
 * @throws IllegalArgumentException if any argument is undefined
 */
@throws[IllegalArgumentException]
abstract class WhiskEntity protected[entity] (en: EntityName) extends WhiskDocument {

    val namespace: EntityPath
    val name = en
    val version: SemVer
    val publish: Boolean
    val annotations: Parameters
    val updated = Instant.now(Clock.systemUTC())

    /**
     * The name of the entity qualified with its namespace and version for
     * creating unique keys in backend services.
     */
    final def fullyQualifiedName(withVersion: Boolean) = FullyQualifiedEntityName(namespace, en, if (withVersion) Some(version) else None)

    /** The primary key for the entity in the datastore */
    override final def docid = fullyQualifiedName(false).toDocId

    /**
     * Returns a JSON object with the fields specific to this abstract class.
     */
    protected def entityDocumentRecord: JsObject = JsObject(
        "name" -> JsString(name.toString),
        "updated" -> JsNumber(updated.toEpochMilli()))

    override def toDocumentRecord: JsObject = {
        val extraFields = entityDocumentRecord.fields
        val base = super.toDocumentRecord

        // In this order to make sure the subclass can rewrite using toJson.
        JsObject(extraFields ++ base.fields)
    }

    /**
     * @return the primary key (name) of the entity as a pithy description
     */
    override def toString = s"${this.getClass.getSimpleName}/${fullyQualifiedName(true)}"

    /**
     * A JSON view of the entity, that should match the result returned in a list operation.
     * This should be synchronized with the views computed in wipeTransientDBs.sh.
     */
    def summaryAsJson = JsObject(
        "namespace" -> namespace.toJson,
        "name" -> name.toJson,
        "version" -> version.toJson,
        WhiskEntity.sharedFieldName -> JsBoolean(publish),
        "annotations" -> annotations.toJsArray)
}

object WhiskEntity {

    val sharedFieldName = "publish"
    val paramsFieldName = "parameters"
    val annotationsFieldName = "annotations"

    /**
     * Gets fully qualified name of an activation based on its namespace and activation id.
     */
    def qualifiedName(namespace: EntityPath, activationId: ActivationId) = {
        s"$namespace${EntityPath.PATHSEP}$activationId"
    }
}

/**
 * Dispatches to appropriate serdes. This object is not itself implicit so as to
 * avoid multiple implicit alternatives when working with one of the subtypes.
 */
object WhiskEntityJsonFormat extends RootJsonFormat[WhiskEntity] {
    // THE ORDER MATTERS! E.g. some triggers can deserialize as packages, but not
    // the other way around. Try most specific first!
    private def readers: Stream[JsValue => WhiskEntity] = Stream(
        WhiskAction.serdes.read,
        WhiskActivation.serdes.read,
        WhiskRule.serdes.read,
        WhiskTrigger.serdes.read,
        WhiskPackage.serdes.read)

    // Not necessarily the smartest way to go about this. In theory, whenever
    // a more precise type is known, this method shouldn't be used.
    override def read(js: JsValue): WhiskEntity = {
        val successes: Stream[WhiskEntity] = readers.flatMap(r => Try(r(js)).toOption)
        successes.headOption.getOrElse {
            throw DocumentUnreadable(Messages.corruptedEntity)
        }
    }

    override def write(we: WhiskEntity): JsValue = we match {
        case a: WhiskAction     => WhiskAction.serdes.write(a)
        case a: WhiskActivation => WhiskActivation.serdes.write(a)
        case p: WhiskPackage    => WhiskPackage.serdes.write(p)
        case r: WhiskRule       => WhiskRule.serdes.write(r)
        case t: WhiskTrigger    => WhiskTrigger.serdes.write(t)
    }
}

/**
 * Trait for the objects we want to size. The size will be defined as ByteSize.
 */
trait ByteSizeable {
    /**
     * Method to calculate the size of the object.
     * The size of the object is defined as the sum of sizes of all parameters, that is stored in the object.
     *
     * @return the size of the object as ByteSize
     */
    def size: ByteSize
}

object LimitedWhiskEntityPut extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat3(LimitedWhiskEntityPut.apply)
}

case class SizeError(field: String, is: ByteSize, allowed: ByteSize)

case class LimitedWhiskEntityPut(
    exec: Option[Exec] = None,
    parameters: Option[Parameters] = None,
    annotations: Option[Parameters] = None) {

    def isWithinSizeLimits: Option[SizeError] = {
        exec.flatMap { e =>
            val is = e.size
            if (is <= Exec.sizeLimit) None else Some {
                SizeError(WhiskAction.execFieldName, is, Exec.sizeLimit)
            }
        } orElse parameters.flatMap { p =>
            val is = p.size
            if (is <= Parameters.sizeLimit) None else Some {
                SizeError(WhiskEntity.paramsFieldName, is, Parameters.sizeLimit)
            }
        } orElse annotations.flatMap { a =>
            val is = a.size
            if (is <= Parameters.sizeLimit) None else Some {
                SizeError(WhiskEntity.annotationsFieldName, is, Parameters.sizeLimit)
            }
        }
    }
}
