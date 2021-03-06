package com.prisma.api.connector.jdbc.database

import com.prisma.api.connector.PrismaArgs
import com.prisma.api.schema.APIErrors.FieldCannotBeNull
import com.prisma.gc_values._
import com.prisma.shared.models.TypeIdentifier.IdTypeIdentifier
import com.prisma.shared.models.{Model, TypeIdentifier}

import scala.concurrent.ExecutionContext

trait NodeActions extends BuilderBase with FilterConditionBuilder with ScalarListActions with RelayIdActions {
  import slickDatabase.profile.api._

  def createNode(model: Model, args: PrismaArgs): DBIO[IdGCValue] = {
    val idIsAutoGeneratedByDb = model.idField_!.isAutoGeneratedByDb
    val argsWithIdIfNecessary = if (idIsAutoGeneratedByDb) args.rootGC else args.rootGC.add(model.idField_!.name, generateId(model))

    val fields = model.fields.filter(field => argsWithIdIfNecessary.hasArgFor(field.name))
    val query = sql
      .insertInto(modelTable(model))
      .columns(fields.map(modelColumn): _*)
      .values(placeHolders(fields))
      .returning(modelColumn(model.idField_!))

    insertReturningGeneratedKeysToDBIO(query)(
      setParams = { pp =>
        fields.foreach { field =>
          pp.setGcValue(argsWithIdIfNecessary.map(field.name))
        }
      },
      readResult = { rs =>
        if (idIsAutoGeneratedByDb) {
          rs.next()
          rs.getId(model)
        } else {
          argsWithIdIfNecessary.idField
        }
      }
    )
  }

  private def generateId(model: Model) = {
    model.idField_!.typeIdentifier.asInstanceOf[IdTypeIdentifier] match {
      case TypeIdentifier.UUID => UuidGCValue.random
      case TypeIdentifier.Cuid => StringIdGCValue.random
      case TypeIdentifier.Int  => sys.error("can't generate int ids")
    }
  }

  def updateNodeById(model: Model, id: IdGCValue, updateArgs: PrismaArgs)(implicit ec: ExecutionContext): DBIO[_] = {
    updateNodesByIds(model, updateArgs, Vector(id))
  }

  def updateNodesByIds(model: Model, args: PrismaArgs, ids: Vector[IdGCValue]): DBIO[_] = {
    if (args.isEmpty || ids.isEmpty) {
      dbioUnit
    } else {
      val aliasedTable = modelTable(model)

      val columns = args.rootGCMap.map {
        case (k, v) =>
          val field = model.getFieldByName_!(k)
          if (field.isRequired && v == NullGCValue) throw FieldCannotBeNull(field.name)
          field.dbName
      }.toList

      val query = sql
        .update(aliasedTable)
        .setColumnsWithPlaceHolders(columns)
        .where(idField(model).in(placeHolders(ids)))

      updateToDBIO(query)(
        setParams = pp => {
          args.rootGCMap.foreach { case (_, v) => pp.setGcValue(v) }
          ids.foreach(pp.setGcValue)
        }
      )
    }
  }

  def deleteNodeById(model: Model, id: IdGCValue, shouldDeleteRelayIds: Boolean)(implicit ec: ExecutionContext) = {
    deleteNodes(model, Vector(id), shouldDeleteRelayIds)
  }

  def deleteNodes(model: Model, ids: Vector[IdGCValue], shouldDeleteRelayIds: Boolean)(implicit ec: ExecutionContext): DBIO[Unit] = {
    DBIO.seq(
      deleteScalarListValuesByNodeIds(model, ids),
      if (shouldDeleteRelayIds) deleteRelayIds(ids) else dbioUnit,
      deleteNodesByIds(model, ids)
    )
  }

  private def deleteNodesByIds(model: Model, ids: Vector[IdGCValue]): DBIO[Unit] = {
    val query = sql
      .deleteFrom(modelTable(model))
      .where(idField(model).in(placeHolders(ids)))

    deleteToDBIO(query)(
      setParams = pp => ids.foreach(pp.setGcValue)
    )
  }

  private val dbioUnit = DBIO.successful(())
}
