package br.ufrn.backhoe.repminer.miner.szz.util;

import br.ufrn.backhoe.repminer.miner.szz.model.Line;
import br.ufrn.backhoe.repminer.miner.szz.model.RelationTypes;
import br.ufrn.backhoe.repminer.miner.szz.model.NodeDb;

import br.ufrn.backhoe.persistence.*;
import java.util.*;
import org.apache.log4j.Logger;

public class GraphDatabaseOperationsRdb {

	private static LinkedIssueSvnDAO dao;
	private List<NodeDb> nodesToPersist;

	public static void setDao(LinkedIssueSvnDAO myDao){
		dao = myDao;
	}

	public GraphDatabaseOperationsRdb() {
		nodesToPersist = new ArrayList<NodeDb>();
	}

	public NodeDb createLineNode(String path, int number, long revision, String content, String project,
			String fname) {
		NodeDb lineNode = null;
		String id = fname + "#" + revision + "#" + number; 
		//lineNode = getNodeFromDB(path + "#" + revision + "#" + number);
		//if(lineNode == null){
		//if (nodesIdsBuffer.contains(id)) {
		lineNode = new NodeDb(path, revision, number, content, project, id);
		if(!nodesToPersist.contains(lineNode)){
			nodesToPersist.add(lineNode);
		} else {
			//updating information
			lineNode = nodesToPersist.get(nodesToPersist.indexOf(lineNode));
		}
		//}
		return lineNode;
	}

	public NodeDb getNodeFromDB(String id) {
		NodeDb node = dao.getExistingNode(id);
		return node;
	}

	public NodeDb createEvolvingRelationship(NodeDb firstNode, NodeDb secondNode) {
		firstNode.getEvolutions().add(secondNode.getId());
		return firstNode;
	}

	public NodeDb createOriginatesRelationship(NodeDb firstNode, NodeDb secondNode) {
		firstNode.getOrigins().add(secondNode.getId());
		return firstNode;
	}

	public List<NodeDb> getNodesToPersist(){
		return nodesToPersist;
	}	
}
