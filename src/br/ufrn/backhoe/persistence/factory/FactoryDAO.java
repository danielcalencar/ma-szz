package br.ufrn.backhoe.persistence.factory;

import br.ufrn.backhoe.persistence.LinkedIssueSvnDAO;

import br.ufrn.backhoe.repminer.enums.DAOType;

public abstract class FactoryDAO 
{
	public abstract LinkedIssueSvnDAO getLinkedIssueSvnDAO();

	public static FactoryDAO getFactoryDAO(DAOType type) {
		if(type == DAOType.HIBERNATE)
		{
			return new HibernateFactoryDAO();
		}
		else
		{
			return null;
		}
	}
	
}
