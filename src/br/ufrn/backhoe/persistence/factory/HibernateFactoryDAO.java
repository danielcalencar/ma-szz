package br.ufrn.backhoe.persistence.factory;

import br.ufrn.backhoe.persistence.HibernateLinkedIssueSvnDAO;

public class HibernateFactoryDAO extends FactoryDAO {
	
	/* (non-Javadoc)
	 * @see br.ufrn.backhoe.persistence.factory.FactoryDAO#getHibernateLinkedIssueSvnDAO()Li
	 */
	@Override
	public HibernateLinkedIssueSvnDAO getLinkedIssueSvnDAO() {
		return new HibernateLinkedIssueSvnDAO();
	}


}

