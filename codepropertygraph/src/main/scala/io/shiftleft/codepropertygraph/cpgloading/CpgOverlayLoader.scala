package io.shiftleft.codepropertygraph.cpgloading

import java.io.IOException

import io.shiftleft.codepropertygraph.Cpg
import java.lang.{Long => JLong}
import java.util.{ArrayList => JArrayList}

import io.shiftleft.proto.cpg.Cpg.{CpgOverlay, PropertyValue}
import org.apache.tinkerpop.gremlin.structure.{T, Vertex, VertexProperty}

import scala.jdk.CollectionConverters._
import scala.collection.mutable
import gremlin.scala._
import io.shiftleft.codepropertygraph.generated.nodes.StoredNode
import io.shiftleft.passes.DiffGraph
import org.apache.logging.log4j.LogManager

import scala.collection.mutable.ArrayBuffer

private[cpgloading] object CpgOverlayLoader {
  private val logger = LogManager.getLogger(getClass)

  /**
    * Load overlays stored in the file with the name `filename`.
    */
  def load(filename: String, baseCpg: Cpg): Unit = {
    val applier = new CpgOverlayApplier(baseCpg.graph)
    ProtoCpgLoader
      .loadOverlays(filename)
      .map { overlays: Iterator[CpgOverlay] =>
        overlays.foreach(applier.applyDiff)
      }
      .recover {
        case e: IOException =>
          logger.error("Failed to load overlay from " + filename, e)
          Nil
      }
      .get
  }
}

/**
  * Component to merge CPG overlay into existing graph
  *
  * @param graph the existing (loaded) graph to apply overlay to
  */
private class CpgOverlayApplier(graph: ScalaGraph) {
  private val overlayNodeIdToSrcGraphNode: mutable.HashMap[Long, Vertex] = mutable.HashMap()

  /**
    * Applies diff to existing (loaded) OdbGraph
    */
  def applyDiff(overlay: CpgOverlay): Unit = {
    val inverseBuilder: DiffGraph.InverseBuilder = DiffGraph.InverseBuilder.noop
    addNodes(overlay, inverseBuilder)
    addEdges(overlay, inverseBuilder)
    addNodeProperties(overlay, inverseBuilder)
    addEdgeProperties(overlay, inverseBuilder)
  }

  def applyUndoableDiff(overlay: CpgOverlay): DiffGraph = {
    val inverseBuilder: DiffGraph.InverseBuilder = DiffGraph.InverseBuilder.newBuilder
    addNodes(overlay, inverseBuilder)
    addEdges(overlay, inverseBuilder)
    addNodeProperties(overlay, inverseBuilder)
    addEdgeProperties(overlay, inverseBuilder)
    inverseBuilder.build()
  }

  private def makeInverseBuilder(undoable: Boolean) = {
    if (undoable)
      DiffGraph.InverseBuilder.newBuilder
    else
      DiffGraph.InverseBuilder.noop
  }

  private def addNodes(overlay: CpgOverlay, inverseBuilder: DiffGraph.InverseBuilder): Unit = {
    assert(graph.graph.features.vertex.supportsUserSuppliedIds,
           "this currently only works for graphs that allow user supplied ids")

    overlay.getNodeList.asScala.foreach { node =>
      val id = node.getKey
      val properties = node.getPropertyList.asScala

      val keyValues = new ArrayBuffer[AnyRef](4 + (2 * properties.size))
      keyValues += T.id
      keyValues += (node.getKey: JLong)
      keyValues += T.label
      keyValues += node.getType.name
      properties.foreach { property =>
        ProtoToCpg.addProperties(keyValues, property.getName.name, property.getValue)
      }
      val newNode = graph.graph.addVertex(keyValues.toArray: _*)
      inverseBuilder.onNewNode(newNode.asInstanceOf[StoredNode])
      overlayNodeIdToSrcGraphNode.put(id, newNode)
    }
  }

  private def addEdges(overlay: CpgOverlay, inverseBuilder: DiffGraph.InverseBuilder) = {
    overlay.getEdgeList.asScala.foreach { edge =>
      val srcTinkerNode = getVertexForOverlayId(edge.getSrc)
      val dstTinkerNode = getVertexForOverlayId(edge.getDst)

      val properties = edge.getPropertyList.asScala
      val keyValues = new ArrayBuffer[AnyRef](2 * properties.size)
      properties.foreach { property =>
        ProtoToCpg.addProperties(keyValues, property.getName.name, property.getValue)
      }
      val newEdge =
        srcTinkerNode.addEdge(edge.getType.toString, dstTinkerNode, keyValues.toArray: _*)
      inverseBuilder.onNewEdge(newEdge)
    }
  }

  private def addNodeProperties(overlay: CpgOverlay, inverseBuilder: DiffGraph.InverseBuilder): Unit = {
    overlay.getNodePropertyList.asScala.foreach { additionalNodeProperty =>
      val property = additionalNodeProperty.getProperty
      val tinkerNode = getVertexForOverlayId(additionalNodeProperty.getNodeId)
      addPropertyToElement(tinkerNode, property.getName.name, property.getValue, inverseBuilder)
    }
  }

  private def addEdgeProperties(overlay: CpgOverlay, inverseBuilder: DiffGraph.InverseBuilder): Unit = {
    overlay.getEdgePropertyList.asScala.foreach { additionalEdgeProperty =>
      throw new RuntimeException("Not implemented.")
    }
  }

  private def getVertexForOverlayId(id: Long): Vertex = {
    if (overlayNodeIdToSrcGraphNode.contains(id)) {
      overlayNodeIdToSrcGraphNode(id)
    } else {
      val iter = graph.graph.vertices(id.asInstanceOf[Object])
      assert(iter.hasNext, s"node with id=$id neither found in overlay nodes, nor in existing graph")
      nextChecked(iter)
    }
  }

  private def addPropertyToElement(tinkerElement: Element,
                                   propertyName: String,
                                   propertyValue: PropertyValue,
                                   inverseBuilder: DiffGraph.InverseBuilder): Unit = {
    import PropertyValue.ValueCase._
    tinkerElement match {
      case storedNode: StoredNode =>
        inverseBuilder.onBeforeNodePropertyChange(storedNode, propertyName)
      case edge: Edge =>
        inverseBuilder.onBeforeEdgePropertyChange(edge, propertyName)
    }
    propertyValue.getValueCase match {
      case INT_VALUE =>
        tinkerElement.property(propertyName, propertyValue.getIntValue)
      case STRING_VALUE =>
        tinkerElement.property(propertyName, propertyValue.getStringValue)
      case BOOL_VALUE =>
        tinkerElement.property(propertyName, propertyValue.getBoolValue)
      case STRING_LIST if tinkerElement.isInstanceOf[Vertex] =>
        propertyValue.getStringList.getValuesList.forEach { value: String =>
          tinkerElement
            .asInstanceOf[Vertex]
            .property(VertexProperty.Cardinality.list, propertyName, value)
        }
      case STRING_LIST =>
        val propertyList = new JArrayList[AnyRef]()
        propertyList.addAll(propertyValue.getStringList.getValuesList)
        tinkerElement.property(propertyName, propertyList)
      case VALUE_NOT_SET =>
      case valueCase =>
        throw new RuntimeException("Error: unsupported property case: " + valueCase)
    }
  }

  private def nextChecked[T](iterator: java.util.Iterator[T]): T = {
    try {
      iterator.next
    } catch {
      case _: NoSuchElementException =>
        throw new NoSuchElementException()
    }
  }

}
