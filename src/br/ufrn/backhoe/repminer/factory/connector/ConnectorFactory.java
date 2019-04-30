package br.ufrn.backhoe.repminer.factory.connector;

import br.ufrn.backhoe.repminer.connector.Connector;

public abstract class ConnectorFactory 
{
	public abstract Connector<?> createConnector(String user, String password, String url) throws Exception;
	
}
