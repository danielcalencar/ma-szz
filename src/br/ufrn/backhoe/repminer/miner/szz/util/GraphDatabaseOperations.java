package br.ufrn.backhoe.repminer.miner.szz.util;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;

import br.ufrn.backhoe.repminer.miner.szz.model.Line;
import br.ufrn.backhoe.repminer.miner.szz.model.RelationTypes;

public class GraphDatabaseOperations {

	private GraphDatabaseService gph;
	private Label label;
	private Label labelRevisionPath;

	public GraphDatabaseOperations(String path, Label label, Label labelRevisionPath) {
		this.gph = new GraphDatabaseFactory().newEmbeddedDatabase(path);
		this.label = label;
		this.labelRevisionPath = labelRevisionPath;
	}

	public GraphDatabaseOperations(GraphDatabaseService gph, String path, Label label, Label labelRevisionPath) {
		this.gph = gph;
		this.label = label;
		this.labelRevisionPath = labelRevisionPath;
	}

	public Node createLineNode(Line line, String path, int number, long revision, String content, String project) {
		//Transaction tx = gph.beginTx();
		Node lineNode = null;
		try {
			lineNode = getNodeFromDB(path + "#" + revision + "#" + number);
			if (lineNode == null) {
				lineNode = gph.createNode();
				lineNode.setProperty("pathname", path);
				lineNode.setProperty("revision", revision);
				lineNode.setProperty("linenumber", number);
				lineNode.setProperty("content", content);
				lineNode.setProperty("project", project);
				final String id = path + "#" + revision + "#" + number;
				lineNode.setProperty("id", id);
				lineNode.addLabel(label);
			}
			//       tx.success();
			return lineNode;
		} finally {
			//       tx.close();
		}
	}

	public void createRevisionPathNode(long revision, String path){
		Node rpNode = gph.createNode();
		rpNode.setProperty("revision", revision);
		rpNode.setProperty("path", path);
		rpNode.setProperty("id", revision + "#" + path);
	}

	public Node getRevisionPathNode(long revision, String path) {
		Node node = null;
		String id = revision + "#" + path;
		ResourceIterator<Node> nodes = gph.findNodesByLabelAndProperty(labelRevisionPath, "id", id).iterator();
		try{
			while (nodes.hasNext()) {
				node = nodes.next();
			}
		} finally {
			nodes.close();
		}
		return node;
	}


       // public Node createLineNode(Line line, String path, int number, long revision, String content) {
       // 	//Transaction tx = gph.beginTx();
       // 	Node lineNode = null;
       // 	try {
       // 		lineNode = getNodeFromDB(path + "#" + revision + "#" + number);
       // 		if (lineNode == null) {
       // 			lineNode = gph.createNode();
       // 			lineNode.setProperty("pathname", path);
       // 			lineNode.setProperty("revision", revision);
       // 			lineNode.setProperty("linenumber", number);
       // 			lineNode.setProperty("content", content);
       // 			final String id = path + "#" + revision + "#" + number;
       // 			lineNode.setProperty("id", id);
       // 			lineNode.addLabel(label);
       // 		}
       // 		//       tx.success();
       // 		return lineNode;
       // 	} finally {
       // 		//       tx.close();
       // 	}
       // }

	public Node getNodeFromDB(String id) {
		Node node = null;
		ResourceIterator<Node> nodes = gph.findNodesByLabelAndProperty(label, "id", id).iterator();
		try {
			while (nodes.hasNext()) {
				node = nodes.next();
			}
		} finally {
			nodes.close();
		}
		return node;
	}

	public Relationship createEvolvingRelationship(Node firstNode, Node secondNode) {
			Relationship rel = firstNode.createRelationshipTo(secondNode, RelationTypes.EVOLVES_TO);
			return rel;
	}

	public Relationship createOriginatesRelationship(Node firstNode, Node secondNode) {
			Relationship rel = firstNode.createRelationshipTo(secondNode, RelationTypes.ORIGINATED_FROM);
			return rel;
	}

	public GraphDatabaseService getGph() {
		return gph;
	}
}
