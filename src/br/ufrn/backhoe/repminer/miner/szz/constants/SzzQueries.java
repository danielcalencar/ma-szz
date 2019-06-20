package br.ufrn.backhoe.repminer.miner.szz.constants;

public abstract class SzzQueries {
	
	public static final String getOriginsQuery(String id){
		return "match (m)-[r:EVOLVES_TO]->(n) where n.id =\"" + id + "\" return m";
	}
}
