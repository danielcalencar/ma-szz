//java -Xms3g -Xmx6g -cp szz.jar:szz_lib/* br.ufrn.backhor.repminer.miner.szz.SzzRdb ActiveMQ
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
import br.ufrn.backhoe.repminer.miner.szz.workers.ProjectAnnotationGraphBuilderRdb;

public class SzzRdb extends Miner {

	private static final Logger log = Logger.getLogger(SzzRdb.class);
	private String project;
	private LinkedIssueSvnDAO liDao;
	private SubversionConnector connector;
	private String repoUrl;
	private SVNRepository encapsulation;
	private boolean entireDb;
	private Comparator<SVNFileRevision> revisionComp = new Comparator<SVNFileRevision>() {
		@Override
		public int compare(SVNFileRevision o1, SVNFileRevision o2) {
			if (o1.getRevision() > o2.getRevision()) {
				return 1;
			} else if (o1.getRevision() < o2.getRevision()) {
				return -1;
			}
			return 0;
		}
	};

	public static void main(String[] args) throws Exception {
		String project = args[0];
		String user = "";
		String password = "";

		SzzRdb szz = new SzzRdb();
		String url = szz.getProperty("svn_url","./backhoe.properties");
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

	@Override
	public void performSetup() throws Exception {
		log.info("perform setup ... ");
		try {
			this.project = (String) this.getParameters().get("project");
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
		final boolean buildAnnotationGraph = Boolean.valueOf(getProperty("build_graph", "./backhoe.properties"));
		final boolean findBugIntroducingChanges = Boolean.valueOf(getProperty("find_bug_code", "./backhoe.properties"));
		entireDb = true;//Boolean.valueOf(getProperty("entire_db", "./backhoe.properties"));

		if (buildAnnotationGraph) {
			buildAnnotationGraph();
		}
	}


	private void buildAnnotationGraph() throws Exception {
		ProjectAnnotationGraphBuilderRdb.setDao(liDao);
		try {
			ProjectAnnotationGraphBuilderRdb worker = 
				new ProjectAnnotationGraphBuilderRdb(encapsulation, liDao, project, repoUrl, entireDb);
			worker.run();
		} catch ( Exception e ) {
			e.printStackTrace();
		}
	}
}
