package br.ufrn.backhoe.repminer.connector;

import org.tmatesoft.svn.core.io.SVNRepository;

import br.ufrn.backhoe.repminer.connector.Connector;

public class SubversionConnector extends Connector<SVNRepository> 
{
	private String repoUrl;

	public String getRepoUrl() 
	{
		return repoUrl;
	}

	public void setRepoUrl(String repoUrl) 
	{
		this.repoUrl = repoUrl;
	}
	
}
