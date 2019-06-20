//java -Xms3g -Xmx6g -cp szz.jar:szz_lib/* br.ufrn.backhoe.repminer.miner.szz.SzzSliwerski ActiveMQ
package br.ufrn.backhoe.repminer.miner.szz;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.apache.log4j.Logger;
import org.hibernate.Transaction;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNLogEntryPath;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.io.SVNFileRevision;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc2.SvnCat;
import org.tmatesoft.svn.core.wc2.SvnDiff;
import org.tmatesoft.svn.core.wc2.SvnOperationFactory;
import org.tmatesoft.svn.core.wc2.SvnTarget;

import br.ufrn.backhoe.persistence.LinkedIssueSvnDAO;
import br.ufrn.backhoe.persistence.factory.FactoryDAO;
import br.ufrn.backhoe.repminer.connector.Connector;
import br.ufrn.backhoe.repminer.connector.SubversionConnector;
import br.ufrn.backhoe.repminer.enums.ConnectorType;
import br.ufrn.backhoe.repminer.enums.DAOType;
import br.ufrn.backhoe.repminer.factory.connector.SubversionConnectorFactory;
import br.ufrn.backhoe.repminer.miner.Miner;
import br.ufrn.backhoe.repminer.miner.szz.model.BugIntroducingCode;
import br.ufrn.backhoe.repminer.miner.szz.model.DiffHunk;
import br.ufrn.backhoe.repminer.miner.szz.model.Line;
import br.ufrn.backhoe.repminer.miner.szz.model.LineType;
import br.ufrn.backhoe.repminer.miner.szz.model.LinkedRevision;
import br.ufrn.backhoe.repminer.miner.szz.model.Project;
import br.ufrn.backhoe.repminer.miner.szz.model.RelationTypes;
import br.ufrn.backhoe.repminer.miner.szz.workers.FindBugIntroducingChangesSliwerski;

public class SzzSliwerski extends Miner {

	private static final Logger log = Logger.getLogger(SzzSliwerski.class);
	private String project;
	private LinkedIssueSvnDAO liDao;
	private SubversionConnector connector;
	private String repoUrl;
	private SVNRepository encapsulation;
	private boolean entiredb;

	public static void main(String[] args) throws Exception {
		String project = args[0];
		String user = "";
		String password = "";

		SzzSliwerski szz = new SzzSliwerski();
		String url = szz.getProperty("svn_url","./backhoe.properties");
		boolean entiredb = Boolean.valueOf(szz.getProperty("entire_db","./backhoe.properties"));
		Map<ConnectorType, Connector> cs = new HashMap<ConnectorType, Connector>();
		SubversionConnector c = null;
		try {
			c = new SubversionConnectorFactory().createConnector(user, password, url);
		} catch (Exception e){ 
			log.warn("could not initialize SVN");
		}
		cs.put(ConnectorType.SVN, c);
		szz.setConnectors(cs);
		Map p = new HashMap();
		try {
			p.put("project", project);
			p.put("entiredb", entiredb);
			p.put("connector", c);
			p.put("batchSize", 300L);
			p.put("paths", new String[] { "" });
			p.put("existingdata", false);
			szz.setParameters(p);
			szz.executeMining();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void findBugIntroducingChanges() throws Exception {
		try{
			FindBugIntroducingChangesSliwerski worker = 
				new FindBugIntroducingChangesSliwerski(this.project,this.liDao,
						encapsulation, this.entiredb, this.repoUrl);
			worker.run();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void performSetup() throws Exception {
		log.info("perform setup ... ");
		try {
			this.project = (String) this.getParameters().get("project");
			this.entiredb = (boolean) this.getParameters().get("entiredb");
			connector = (SubversionConnector) connectors.get(ConnectorType.SVN);
			repoUrl = connector.getRepoUrl();
			encapsulation = connector.getEncapsulation();
			liDao = (FactoryDAO.getFactoryDAO(DAOType.HIBERNATE)).getLinkedIssueSvnDAO();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	@Override
	public void performMining() throws Exception {
		log.info("perform mining...");
		findBugIntroducingChanges();
	}


}

