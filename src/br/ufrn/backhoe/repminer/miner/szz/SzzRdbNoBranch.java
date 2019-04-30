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

import br.ufrn.backhoe.persistence.CommitDAO;
import br.ufrn.backhoe.persistence.LinkedIssueSvnDAO;
import br.ufrn.backhoe.persistence.factory.FactoryDAO;
import br.ufrn.backhoe.repminer.connector.Connector;
import br.ufrn.backhoe.repminer.connector.SubversionConnector;
import br.ufrn.backhoe.repminer.enums.ConnectorType;
import br.ufrn.backhoe.repminer.enums.DAOType;
import br.ufrn.backhoe.repminer.factory.connector.SubversionConnectorFactory;
import br.ufrn.backhoe.repminer.miner.Miner;
import br.ufrn.backhoe.repminer.miner.strategy.FindFixBug;
import br.ufrn.backhoe.repminer.miner.strategy.FindFixBugDatabase;
import br.ufrn.backhoe.repminer.miner.strategy.FindFixBugFactory;
import br.ufrn.backhoe.repminer.miner.szz.constants.SzzQueries;
import br.ufrn.backhoe.repminer.miner.szz.model.BugIntroducingCode;
import br.ufrn.backhoe.repminer.miner.szz.model.DiffHunk;
import br.ufrn.backhoe.repminer.miner.szz.model.Line;
import br.ufrn.backhoe.repminer.miner.szz.model.LineType;
import br.ufrn.backhoe.repminer.miner.szz.model.LinkedRevision;
import br.ufrn.backhoe.repminer.miner.szz.model.Project;
import br.ufrn.backhoe.repminer.miner.szz.model.RelationTypes;
import br.ufrn.backhoe.repminer.miner.szz.workers.ProjectAnnotationGraphBuilderRdbNoBranch;
import br.ufrn.backhoe.repminer.model.Archive;
import br.ufrn.backhoe.repminer.model.Commit;
import br.ufrn.backhoe.repminer.model.CommitArchive;
import br.ufrn.backhoe.repminer.model.IssueContents;
import br.ufrn.backhoe.repminer.model.Path;
import java.util.concurrent.Future;
import br.ufrn.backhoe.repminer.miner.szz.workers.FindBugIntroCodeWorkerRdb;

import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.Schema;

/**
 *
 * this class performs the SZZ algorithm
 * with out considering branches
 * this is the szz version presented
 * at: https://www.st.cs.uni-saarland.de/softevo/papers/zimmermann-msr-2006-extended.pdf
 * Rdb means that his szz uses relational database instead of neo4j
 *
 */
public class SzzRdbNoBranch extends Miner {

	private static final Logger log = Logger.getLogger(SzzRdbNoBranch.class);
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

		SzzRdbNoBranch szz = new SzzRdbNoBranch();
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
		final boolean buildAnnotationGraph = Boolean
			.valueOf(getProperty("build_graph", "./backhoe.properties"));
		final boolean findBugIntroducingChanges = Boolean
			.valueOf(getProperty("find_bug_code", "./backhoe.properties"));
		entireDb = Boolean
			.valueOf(getProperty("entire_db", "./backhoe.properties"));

		if (buildAnnotationGraph) {
			buildAnnotationGraph();
		}

		if (findBugIntroducingChanges) {
			findBugIntroducingChanges();
		}
	}

	@Override

	public void validateParameter() throws Exception {
	}


	private void buildAnnotationGraph() throws Exception {
		ProjectAnnotationGraphBuilderRdbNoBranch.setDao(liDao);
		try {
			ProjectAnnotationGraphBuilderRdbNoBranch worker = 
				new ProjectAnnotationGraphBuilderRdbNoBranch(encapsulation, liDao, project, repoUrl, entireDb);
			worker.run();
		} catch ( Exception e ) {
			e.printStackTrace();
		}
	}

        public void findBugIntroducingChanges() throws Exception {
		FindBugIntroCodeWorkerRdb worker =  
			new FindBugIntroCodeWorkerRdb(liDao,encapsulation,project,repoUrl);
		worker.run();
        }
}
