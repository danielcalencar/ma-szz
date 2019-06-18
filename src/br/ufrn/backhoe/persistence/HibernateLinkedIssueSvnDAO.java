package br.ufrn.backhoe.persistence;

import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.text.*;
import java.io.Console;

import br.ufrn.backhoe.repminer.miner.szz.model.*;
import br.ufrn.backhoe.repminer.utils.*;
import java.math.BigInteger;

import org.hibernate.SQLQuery;

import org.apache.log4j.Logger;

public class HibernateLinkedIssueSvnDAO extends LinkedIssueSvnDAO {

	private static final Logger log = Logger.getLogger(HibernateLinkedIssueSvnDAO.class);
	private Console c = System.console();

	@Override
	public void prepareBatchInsert() {
	}

	@Override
	public void addBatch(Object obj) {
	}

	@Override
	public int[] executeBatch() {
		return null;
	}

	@Override
	public Object load(Long id) {
		return null;
	}

	@Override
	public void persist(Object obj) {
	}

	@Override
	public void update(Object obj) {
	}

	@Override
	public void remove(Object obj) {
	}

       public synchronized List<Long> getLinkedRevisions() {
		String sql = "select distinct(revisionnumber) from linkedissuessvn";
		List<Long> revisionsConverted = new ArrayList<Long>();
		List<String> revisions = new ArrayList<String>();
		SQLQuery query = currentSession.createSQLQuery(sql);
		revisions = query.list();

		for (String revision : revisions) {
			long revisionconverted = Long.valueOf(revision);
			revisionsConverted.add(revisionconverted);
		}
		return revisionsConverted;
	}

	public synchronized List<Long> getLinkedRevisionWAffectedVersions() {
		String sql = "select distinct(revisionnumber) from linkedissuessvn where issuecode "
				+ "in (select max(ic.bug_id) from issuecontents ic inner join "
				+ "issuecontents_affectedversions iav on ic.id = iav.issuecontents_id group by ic.id);";
		List<Long> revisionsConverted = new ArrayList<Long>();
		List<String> revisions = new ArrayList<String>();
		SQLQuery query = currentSession.createSQLQuery(sql);
		revisions = query.list();

		for (String revision : revisions) {
			long revisionconverted = Long.valueOf(revision);
			revisionsConverted.add(revisionconverted);
		}
		return revisionsConverted;
	}
       
	public synchronized List<Long> getLinkedRevisionWAffectedVersions(String project) {
		String sql = "select distinct(revisionnumber\\:\\:bigint) from linkedissuessvn lsvn " +
			"inner join issuecontents ic on lsvn.issuecode = ic.bug_id " +
			"inner join issuecontents_affectedversions ica on ic.id = ica.issuecontents_id " +
			"inner join release r on ica.affectedversions = r.version and lsvn.projectname like r.project " +
			"where projectname like :project " +
			"and lsvn.issuetype = 'Bug' " +
			"order by revisionnumber\\:\\:bigint";

		List<Long> revisionsConverted = new ArrayList<Long>();
		List<BigInteger> revisions = new ArrayList<BigInteger>();
		SQLQuery query = currentSession.createSQLQuery(sql);
		query.setParameter("project", project);
		revisions = query.list();

		for (BigInteger revision : revisions) {
			long revisionconverted = revision.longValue();
			revisionsConverted.add(revisionconverted);
		}
		return revisionsConverted;
	}

	public synchronized List<Long> getLinkedRevisions(String project) {
		String sql = "select distinct(revisionnumber\\:\\:bigint) from linkedissuessvn lsvn " +
			"where projectname like :project " +
			"and issuetype = 'Bug' " +
			"order by revisionnumber\\:\\:bigint";

		List<Long> revisionsConverted = new ArrayList<Long>();
		List<BigInteger> revisions = new ArrayList<BigInteger>();
		SQLQuery query = currentSession.createSQLQuery(sql);
		query.setParameter("project", project);
		revisions = query.list();

		for (BigInteger revision : revisions) {
			long revisionconverted = revision.longValue();
			revisionsConverted.add(revisionconverted);
		}
		return revisionsConverted;
	}

	public synchronized List<Object[]> getIssuesWithAffectedVersion(String project) {
		String sql = "select lsvn.issuecode, min(r.releasedate), max(lsvn.createddate) " +
			"from linkedissuessvn lsvn " + 
			"inner join issuecontents ic on lsvn.issuecode = ic.bug_id " +
			"inner join issuecontents_affectedversions ica on ic.id = ica.issuecontents_id " +
			"inner join release r " +
			"on ica.affectedversions = r.version " + 
			"and lsvn.projectname like r.project " +
			"where lsvn.projectname = :project " +
			"and lsvn.issuetype = 'Bug' " +
			"group by lsvn.issuecode " +
			"order by lsvn.issuecode "; 

		SQLQuery query = currentSession.createSQLQuery(sql);
		query.setParameter("project", project);
		List<Object[]> issueDate = (List<Object[]>) query.list();

		return issueDate;
	}

	public synchronized long getLastRevisionProcessed(String project){
		String sql = "select lastrevisionprocessed from szz_project_lastrevisionprocessed " +
			"where project = :project";
		SQLQuery query = currentSession.createSQLQuery(sql);
		query.setParameter("project",project);
		BigInteger lastProcessedRevision = (BigInteger) query.uniqueResult(); 
		if(lastProcessedRevision == null){
			return 0L;
		} else {
			return lastProcessedRevision.longValue();
		}
	}
	
	
	public synchronized void insertProjectRevisionsProcessed(String project){
		String sql = "insert into szz_project_lastrevisionprocessed values (:param1,:param2)";
		executeSQLWithParams(sql,project,0L);
	}


	public synchronized void updateProjectRevisionsProcessed(String project, long revision){
		String sql = "update szz_project_lastrevisionprocessed set lastrevisionprocessed = :param1 " +
			"where project = :param2";
		executeSQLWithParams(sql,revision,project);
	} 

	public synchronized void insertBugIntroducingCode(BugIntroducingCode bicode){
		String sql = "insert into bugintroducingcode values (:param1,:param2, :param3, " + 
			":param4, :param5, :param6, :param7, :param8, :param9, :param10, :param11, " +  
			":param12,:param13, :param14)";
		executeSQLWithParams(sql,bicode.getLinenumber(), bicode.getPath(), bicode.getContent(),
				bicode.getRevision(), bicode.getFixRevision(), bicode.getProject(), 
				bicode.getSzzDate(),bicode.getCopypath(),bicode.getCopyrevision(),
				bicode.getMergerev(),bicode.getBranchrev(),bicode.getChangeproperty(),
				bicode.getMissed(),bicode.getFurtherback());	       
	}

	public synchronized Date getReportingDateFromRevision(String revision){
		String sql = " select ic.creationdate from linkedissuessvn lsvn inner join " + 
			"issuecontents ic on lsvn.issuecode = ic.bug_id where lsvn.issuecode " + 
        		" in (select max(ic2.bug_id) from issuecontents ic2 inner join " + 
			" issuecontents_affectedversions iav on ic2.id = iav.issuecontents_id group by ic2.id) " + 
			" and lsvn.revisionnumber = :revision";
		log.info("trying to get reporting date");
		SQLQuery query = currentSession.createSQLQuery(sql);
		query.setParameter("revision",revision);
		Date result = (Date) query.uniqueResult();
		log.info("date gotten");
		return result;
	}

}
