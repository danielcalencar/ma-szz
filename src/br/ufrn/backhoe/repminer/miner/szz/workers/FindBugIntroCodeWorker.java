package br.ufrn.backhoe.repminer.miner.szz.workers;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import java.util.Date;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Iterator;

import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNLogEntryPath;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.io.SVNFileRevision;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc2.SvnTarget;
import org.tmatesoft.svn.core.wc2.SvnDiff;
import org.tmatesoft.svn.core.wc2.SvnOperationFactory;
import br.ufrn.backhoe.persistence.LinkedIssueSvnDAO;

import br.ufrn.backhoe.repminer.miner.szz.model.Project;
import br.ufrn.backhoe.repminer.miner.szz.constants.SzzQueries;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.helpers.collection.IteratorUtil;
import br.ufrn.backhoe.repminer.miner.szz.model.DiffHunk;
import java.util.Comparator;
import java.io.IOException;
import br.ufrn.backhoe.repminer.miner.szz.model.BugIntroducingCode;
import br.ufrn.backhoe.repminer.miner.szz.model.DiffHunk;
import br.ufrn.backhoe.repminer.miner.szz.model.Line;
import br.ufrn.backhoe.repminer.miner.szz.model.LineType;

import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.cypher.javacompat.ExecutionResult;                                       
import org.apache.log4j.Logger;

public class FindBugIntroCodeWorker implements Runnable {

	private static final Logger log = Logger.getLogger(FindBugIntroCodeWorker.class);
	private SVNRepository encapsulation;
	private LinkedIssueSvnDAO liDao;
	private String path;
	private GraphDatabaseService gph;
       	private Label label;
	private Project project;
	private String repoUrl;	
	private ExecutionEngine engine;
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


        public FindBugIntroCodeWorker(String path, LinkedIssueSvnDAO liDao, SVNRepository encapsulation,
			GraphDatabaseService gph, Label label, Project project, String repoUrl){
		this.path = path;
		this.liDao = liDao;
		this.encapsulation = encapsulation;
		this.gph = gph;
		this.label = label;
		this.project = project;
		this.repoUrl = repoUrl;
		this.engine = new ExecutionEngine(gph);
	}

	@Override
	public void run(){
		try { 
			persist();
		} catch (Exception e){
			e.printStackTrace();
		}

	}

	public void persist() throws Exception {
		
		final List<Long> linkedRevs = liDao.getLinkedRevisionWAffectedVersions(project.getProject());
		log.info("revisions to process: " + linkedRevs.size());   
		int count = 1;
		for(long revision : linkedRevs){
			log.info("processing " + count + " revisions of " + linkedRevs.size());   
			List<SVNLogEntry> logEntries = new ArrayList<SVNLogEntry>();

			try{ 
				encapsulation.log(new String[] { "" }, logEntries, revision, revision, true, false);
			} catch (SVNException svne) {
				svne.printStackTrace();
				continue;
			}
			SVNLogEntry entry = logEntries.get(0);
			Date reportingDate = liDao.getReportingDateFromRevision(String.valueOf(revision));

			for (SVNLogEntryPath ep : entry.getChangedPaths().values()) {
				final String type = ep.getKind().toString();
				final String path = ep.getPath();
				final char action = ep.getType();

				//we are not interested on additions
				if(action == SVNLogEntryPath.TYPE_ADDED){
					continue;	
				}
				if (!type.equals("file")) {
					continue;
				}
				
				String[] tokens = path.split("/");
				String lastPart = tokens[tokens.length - 1];
				if (tokens.length > 0 && (lastPart.contains(".txt") || lastPart.contains("CHANGELOG") || lastPart.contains("pom.xml")
							|| lastPart.contains("project.xml") || lastPart.contains(".xml"))) {
					continue;
				}

				final LinkedList<SVNFileRevision> fileRevisions = new LinkedList<SVNFileRevision>();
				try {
					encapsulation.getFileRevisions(path, fileRevisions, 0L, entry.getRevision());
				} catch (SVNException e) {
					if (e.getMessage().contains("is not a file in revision")) {
						continue;
					} else {
						throw e;
					}
				}

				if(fileRevisions.size() == 1 || fileRevisions.isEmpty()){
					log.info("we cannot trace back file addition");
					continue;
				}

				Collections.sort(fileRevisions, revisionComp);
				final SVNFileRevision fr = fileRevisions.getLast();
				final SVNFileRevision pFr = fileRevisions.get(fileRevisions.indexOf(fr) - 1);
				if(fr == null || pFr == null){
					continue;
				}
				log.info("### DIFF: " + pFr.getRevision() + "-" + fr.getRevision());
				final ByteArrayOutputStream diff = diffOperation(repoUrl, pFr, fr);
				persistBugIntroducingCodes(diff, fr, pFr, path, reportingDate,project.getProject()) ;
			}
		}
	}


	private void persistBugIntroducingCodes(ByteArrayOutputStream diff, SVNFileRevision fr, SVNFileRevision pfr,
			String path, Date reportingdate, String project) throws Exception {
		List<String> headers = getHeaders(diff);
		List<DiffHunk> hunks = getHunks(diff, headers, pfr.getRevision(), fr.getRevision());
		persistBugIntroducingCodes(hunks, fr, pfr, path, reportingdate, project);
	}

	private void persistBugIntroducingCodes(List<DiffHunk> hunks, SVNFileRevision fr, SVNFileRevision pfr, String path,
			Date reportingdate, String project) throws Exception {

		List<BugIntroducingCode> bicodes = new ArrayList<BugIntroducingCode>();
		for (DiffHunk hunk : hunks) {
			List<Line> linesHunk = hunk.getContent();
			for (Line line : linesHunk) {
				if (isDeletion(line.getContent())) {
					if (isComment(line.getContent().replace("\\-",""))){
						continue;
					}
					log.info("tracing line: " + line.getNumber());
					traceBack(line, path, fr.getRevision(), bicodes, reportingdate, project);
				}
			}
		}
		log.info("persisting " + bicodes.size() + " lines of buggy codes ");
		persistInDatabase(bicodes);

	}
	
	public synchronized void persistInDatabase(List<BugIntroducingCode> bicodes){
		org.hibernate.Transaction tx = liDao.beginTransaction();
		String sql = "insert into bugintroducingcode values (:param1,:param2, :param3, :param4, :param5, :param6)";
		for(BugIntroducingCode bicode : bicodes ){
			liDao.executeSQLWithParams(sql,bicode.getLinenumber(),bicode.getPath(),
					bicode.getContent(),bicode.getRevision(),bicode.getFixRevision(),
					bicode.getProject());
		}
		tx.commit();
	}	

	private void traceBack(Line line, String path, long fixingRevision, List<BugIntroducingCode> result,
			Date reportingdate, String project) throws Exception {
		String id = path + "#" + line.getPreviousRevision() + "#" + line.getPreviousNumber();
		log.info("trace back id: " + id);
		boolean found = false;
		while (!found) {
			ExecutionResult r = null;
			org.neo4j.graphdb.Transaction tx = gph.beginTx();
			try {
				log.info("trying to execute query");
				r = engine.execute(SzzQueries.getOriginsQuery(id));
				log.info("nodes gotten! size of nodes");
				Iterator<Node> m_column = r.columnAs("m");
				List<Node> nodes = IteratorUtil.asList(m_column);
				if (!nodes.isEmpty()) {
					log.info("nodes are not empty");
					for (Node node : nodes) {
						String nodeid = (String) node.getProperty("id");
						String content = (String) node.getProperty("content");
						String npath = (String) node.getProperty("pathname");
						Long nrev = (Long) node.getProperty("revision");
						Integer nnumber = (Integer) node.getProperty("linenumber");
						found = traceBack(nodeid, content, npath, nnumber, nrev, fixingRevision, result, reportingdate, project);
					}
				} else {
					log.info("bug code found: " + id);
					found = true;
					SVNRevision rev = SVNRevision.create(line.getPreviousRevision());
					//if (rev.getDate().compareTo(reportingdate) < 0) {
					BugIntroducingCode b = new BugIntroducingCode();
					b.setFixRevision(fixingRevision);
					b.setContent(line.getContent().replaceFirst("-", ""));
					b.setLinenumber(line.getPreviousNumber());
					b.setPath(path);
					b.setRevision(line.getPreviousRevision());
					b.setProject(project);
					result.add(b);
					//}
				}
			} finally {
				tx.close();
			}
		}
	}


	private boolean traceBack(String id, String content, String path, int number, long revision, long fixingRevision,
			List<BugIntroducingCode> result, Date reportingdate, String project) throws Exception {
		boolean found = false;
		while (!found) {
			log.info("trace back id: " + id);
			ExecutionResult r = null;
			org.neo4j.graphdb.Transaction tx = gph.beginTx();
			try {
				r = engine.execute(SzzQueries.getOriginsQuery(id));
				Iterator<Node> m_column = r.columnAs("m");
				List<Node> nodes = IteratorUtil.asList(m_column);
				if (!nodes.isEmpty()) {
					for (Node node : nodes) {
						String nodeid = (String) node.getProperty("id");
						String ncontent = (String) node.getProperty("content");
						String npath = (String) node.getProperty("pathname");
						Long nrev = (Long) node.getProperty("revision");
						Integer nnumber = (Integer) node.getProperty("linenumber");
						found = traceBack(nodeid, ncontent, npath, nnumber, nrev, fixingRevision, result, reportingdate, project);
					}
				} else {
					log.info("bug code found: " + id);
					found = true;
					//SVNRevision rev = SVNRevision.create(revision);
					//if (rev.getDate().compareTo(reportingdate) < 0) {
					BugIntroducingCode b = new BugIntroducingCode();
					b.setFixRevision(fixingRevision);
					b.setContent(content);
					b.setLinenumber(number);
					b.setPath(path);
					b.setRevision(revision);
					b.setProject(project);
					result.add(b);
					//}
				}
			} finally {
				tx.close();
			}
		}
		return found;
	}
	
	private boolean isComment(String line){
		line = line.replace("\\-","");
		if (line.trim().length() == 0)
			return true;

		boolean result;
		Pattern pattern = Pattern.compile("(?<!.+)^//.+$");
		Matcher matcher = pattern.matcher(line.trim());

		result = matcher.find();
		if (result)
			return true;

		pattern = Pattern.compile("(?<!.+)//(?!.+)");
		matcher = pattern.matcher(line.trim());

		result = matcher.find();
		if (result)
			return true;

		pattern = Pattern.compile("(?<!.+)\\*(?!.+)");
		matcher = pattern.matcher(line.trim());

		result = matcher.find();
		if (result)
			return true;

		pattern = Pattern.compile("(?<!.+)^/\\*.+$");
		matcher = pattern.matcher(line.trim());

		result = matcher.find();
		if (result)
			return true;

		pattern = Pattern.compile("(?<!.+)^\\*.+$");
		matcher = pattern.matcher(line.trim());

		result = matcher.find();
		if (result)
			return true;

		pattern = Pattern.compile("(?<!.+)^\\s+\\*.+$");
		matcher = pattern.matcher(line.trim());

		result = matcher.find();
		if (result)
			return true;

		pattern = Pattern.compile("(?<!.+)^\\*+/.+$");
		matcher = pattern.matcher(line.trim());

		result = matcher.find();
		if (result)
			return true;

		pattern = Pattern.compile("^C:");
		matcher = pattern.matcher(line.trim());

		result = matcher.find();
		if (result)
			return true;
		// pattern = Pattern.compile("(?<!.+)}(?!.+)");
		// matcher = pattern.matcher(line.trim());

		// result = matcher.find();
		// if(result) return true;

		return false;
	}
	
	private ByteArrayOutputStream diffOperation(String repoUrl, SVNFileRevision fr, SVNFileRevision nextFr) {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		final SvnOperationFactory fac = new SvnOperationFactory();
		try {
			final SVNURL target1 = SVNURL.parseURIEncoded(repoUrl + fr.getPath());
			final SVNURL target2 = SVNURL.parseURIEncoded(repoUrl + nextFr.getPath());
			final SVNRevision svnr1 = SVNRevision.create(fr.getRevision());
			final SVNRevision svnr2 = SVNRevision.create(nextFr.getRevision());

			final SvnDiff diff = fac.createDiff();
			diff.setSources(SvnTarget.fromURL(target1, svnr1), SvnTarget.fromURL(target2, svnr2));
			diff.setOutput(output);
			diff.run();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			fac.dispose();
		}
		return output;
	}

	private List<String> getHeaders(ByteArrayOutputStream diff) throws IOException {
		List<String> headers = new ArrayList<String>();
		BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(diff.toByteArray())));

		try {
			while (br.ready()) {
				String line = br.readLine();
				line = line.trim();
				if (isHunkHeader(line)) {
					headers.add(line);
				}
			}
		} finally {
			br.close();
		}
		return headers;
	}

	private List<DiffHunk> getHunks(ByteArrayOutputStream diff, List<String> headers, long revision, long nextRevision)
			throws IOException {
		List<DiffHunk> hunks = new ArrayList<DiffHunk>();
		for (String header : headers) {
			List<Line> deletionsBuffer = new ArrayList<Line>();
			BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(diff.toByteArray())));
			try {
				boolean linkageFlag = false;
				LineType previousType = LineType.CONTEXT;

				boolean headerFound = false;
				boolean startDeletionDistanceCounter = false;
				boolean startAdditionDistanceCounter = false;
				boolean firstContextLines = true;
				int firstContextCounter = 0;

				int deletions = 0;
				int additions = 0;
				int deletionPosition = -1;
				int additionPosition = -1;
				int deletionStartingNumber = getDeletionStartingLineNumber(header);
				int additionStartingNumber = getAdditionStartingLineNumber(header);

				DiffHunk hunk = new DiffHunk();
				hunk.setHeader(header);

				while (br.ready()) {
					String line = br.readLine();
					if (!headerFound && line.trim().equals(header)) {
						headerFound = true;
						continue;
					}

					if (headerFound) {
						Line lineobj = new Line();
						lineobj.setContent(line);
						if (!isHunkHeader(line)) {

							if (isDeletion(line)) {
								firstContextLines = false;
								// comming from an addition
								if (linkageFlag) {
									deletionsBuffer.clear();
								}
								startDeletionDistanceCounter = true;
								lineobj.setType(LineType.DELETION);
								deletionPosition++;
								deletions++;
								lineobj.setPreviousNumber(deletionStartingNumber + deletionPosition);
								lineobj.setPreviousRevision(revision);
								previousType = LineType.DELETION;
								deletionsBuffer.add(lineobj);
								linkageFlag = false;

							} else if (isAddition(line)) {
								firstContextLines = false;
								
								if (previousType == LineType.DELETION) {
									linkageFlag = true;
								}
								startAdditionDistanceCounter = true;
								lineobj.setType(LineType.ADDITION);
								additionPosition++;
								additions++;
								lineobj.setNumber(additionStartingNumber + additionPosition);
								lineobj.setRevision(nextRevision);
								previousType = LineType.ADDITION;

								if (linkageFlag) {
									lineobj.getOrigins().addAll(deletionsBuffer);
									for (Line deletion : deletionsBuffer) {
										deletion.getEvolutions().add(lineobj);
									}
								}
							} else {
								int contextPositionAdjustment = getContextPositionAdjustment(additionStartingNumber,
										deletionStartingNumber);

								lineobj.setType(LineType.CONTEXT);
								lineobj.setPreviousRevision(revision);
								lineobj.setRevision(nextRevision);
								if (startDeletionDistanceCounter && !startAdditionDistanceCounter) {
									deletionPosition++;
									lineobj.setPreviousNumber(deletionStartingNumber + deletionPosition);
									lineobj.setNumber(lineobj.getPreviousNumber() - deletions
											- contextPositionAdjustment);
								}

								if (startAdditionDistanceCounter && !startDeletionDistanceCounter) {
									additionPosition++;
									lineobj.setNumber(additionStartingNumber + additionPosition);
									lineobj.setPreviousNumber(lineobj.getNumber() - additions
											- contextPositionAdjustment);
								}

								if (startDeletionDistanceCounter && startAdditionDistanceCounter) {
									additionPosition++;
									deletionPosition++;
									lineobj.setPreviousNumber(deletionStartingNumber + deletionPosition);
									lineobj.setNumber(additionStartingNumber + additionPosition);
								}
								if (firstContextLines) {
									lineobj.setPreviousNumber(deletionStartingNumber + firstContextCounter
											+ contextPositionAdjustment - 3);
									lineobj.setNumber(deletionStartingNumber + firstContextCounter
											+ contextPositionAdjustment - 3);
									firstContextCounter++;
								}
								previousType = LineType.CONTEXT;
								linkageFlag = false;
								deletionsBuffer.clear();
							}
							hunk.getContent().add(lineobj);
						} else {
							break;
						}
					}
				}
				hunks.add(hunk);
			} finally {
				br.close();
			}
		}
		return hunks;
	}

	private int getDeletionStartingLineNumber(String header) {
		String[] tokens = header.split(" ");
		String toAnalyze = tokens[1];
		String[] tokens2 = toAnalyze.split(",");
		String lineNumberStr = tokens2[0].replace("-", "");
		int lineNumber = Integer.valueOf(lineNumberStr);
		lineNumber = lineNumber + 3; // 3 lines of context
		return lineNumber;
	}

	private int getAdditionStartingLineNumber(String header) {
		String[] tokens = header.split(" ");
		String toAnalyze = tokens[2];
		String[] tokens2 = toAnalyze.split(",");
		String lineNumberStr = tokens2[0].replace("+", "");
		int lineNumber = Integer.valueOf(lineNumberStr);
		lineNumber = lineNumber + 3; // 3 lines of context
		return lineNumber;
	}

	private boolean isHunkHeader(String line) {
		Pattern pattern = Pattern.compile("@@\\s-\\d+,\\d+\\s\\+\\d+,\\d+\\s@@");
		Matcher matcher = pattern.matcher(line);
		return matcher.find();
	}

	private boolean isAddition(String line) {

		boolean result1;
		Pattern pattern = Pattern.compile("^(\\+)");
		Matcher matcher = pattern.matcher(line.trim());
		result1 = matcher.find();

		return result1;
	}

	private boolean isDeletion(String line) {
		boolean result1;
		Pattern pattern = Pattern.compile("^(\\-)");
		Matcher matcher = pattern.matcher(line.trim());
		result1 = matcher.find();

		return result1;
	}
	
	private int getContextPositionAdjustment(int newStartPosition, int prevStartPosition) {
		return newStartPosition - prevStartPosition;
	}
}
