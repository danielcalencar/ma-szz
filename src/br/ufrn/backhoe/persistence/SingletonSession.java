package br.ufrn.backhoe.persistence;

import org.hibernate.Session;

import br.ufrn.backhoe.repminer.utils.HibernateUtil;

public class SingletonSession 
{
	
	private static Session hibernateSession; 
	
	public static Session getSession(String configName)
	{
		if(hibernateSession != null)
		{
			return hibernateSession;
		}
		else
		{
			hibernateSession = HibernateUtil.getMySqlSessionFactory(configName).openSession();
			return hibernateSession;
		}
	}
}
