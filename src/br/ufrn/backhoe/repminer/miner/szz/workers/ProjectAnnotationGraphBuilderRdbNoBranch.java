package br.ufrn.backhoe.repminer.miner.szz.workers;

import static br.ufrn.backhoe.repminer.utils.svnutils.SvnOperationsUtil.*;
import br.ufrn.backhoe.repminer.utils.svnutils.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;
import java.text.*;
import java.util.regex.*;
import br.ufrn.backhoe.repminer.utils.*;

import org.apache.log4j.Logger;
import org.hibernate.Transaction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.tmatesoft.svn.core.*;
import org.tmatesoft.svn.core.io.SVNFileRevision;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc2.SvnCat;
import org.tmatesoft.svn.core.wc2.SvnDiff;
import org.tmatesoft.svn.core.wc2.SvnOperationFactory;
import org.tmatesoft.svn.core.wc2.SvnTarget;

import br.ufrn.backhoe.persistence.LinkedIssueSvnDAO;
import br.ufrn.backhoe.repminer.miner.szz.model.*;
import br.ufrn.backhoe.repminer.miner.szz.constants.SzzRegex;
import br.ufrn.backhoe.repminer.miner.szz.util.GraphDatabaseOperationsRdb;
import java.util.concurrent.Future;

import java.io.File;
import java.io.Console;

public class ProjectAnnotationGraphBuilderRdbNoBranch implements Runnable {

	private static final Logger log = Logger.getLogger(ProjectAnnotationGraphBuilderRdbNoBranch.class);
	private static LinkedIssueSvnDAO liDao;
	private SVNRepository encapsulation;
	private Label label;
	//private GraphDatabaseOperationsRdb gpho;
	private String project;
	private String repoUrl;
	private String gphpath;
	private boolean entireDb;
	private boolean isMergeRevision = false;
	private boolean isBranchRevision = false;
	private List<BugIntroducingCode> bicodes;
	private Console c = System.console();
	//private List<OnlyAdditionCode> additions;
//	private File revisionsNotFound;

	private Comparator<SzzFileRevision> revisionComp = new Comparator<SzzFileRevision>() {
		@Override
		public int compare(SzzFileRevision o1, SzzFileRevision o2) {
			if (o1.getRevision() > o2.getRevision()) {
				return 1;
			} else if (o1.getRevision() < o2.getRevision()) {
				return -1;
			}
			return 0;
		}
	};

	public ProjectAnnotationGraphBuilderRdbNoBranch(String repoUrl, SVNRepository encapsulation){
		this.repoUrl = repoUrl;
		this.encapsulation = encapsulation;
	}

	public ProjectAnnotationGraphBuilderRdbNoBranch(SVNRepository encapsulation, LinkedIssueSvnDAO liDao,
			String project, String repoUrl, boolean entireDb ) {
		this.encapsulation = encapsulation;
		GraphDatabaseOperationsRdb.setDao(liDao);
		this.label = label;
		this.project = project;
		this.repoUrl = repoUrl;
		this.entireDb = entireDb;
		// this.revisionsNotFound = new File("./revisions_not_found.txt");
	}

	public static void setDao(LinkedIssueSvnDAO dao){
		liDao = dao;
	}

	@Override
	public void run() {
		try {
			long starttime = System.nanoTime();
			log.info(Thread.currentThread().getName() + "-" + project + " is running.");
			buildAnnotationGraph();
			long endtime = System.nanoTime();
			c.readLine("duration: " + (endtime - starttime));
		} catch (Exception e) {
			e.printStackTrace();
			c.readLine("ops");
		}
	}

	private boolean buildAnnotationGraph() throws Exception {
		//did the process start before?
		long lastRevisionProcessed = liDao.getLastRevisionProcessed(project);
		if(lastRevisionProcessed == 0L){
			liDao.insertProjectRevisionsProcessed(project);
		}
		List<Long> linkedRevs = null;
		synchronized(liDao){
			if(entireDb){
				linkedRevs = liDao.getLinkedRevisions(project);
			} else {
				linkedRevs = liDao.getLinkedRevisionWAffectedVersions(project);
			}
			//test
			//linkedRevs = new ArrayList<Long>();
			//linkedRevs.add(22L);
		}
		log.info("Project " + project + " starting...");
		log.info(linkedRevs.size() + " Linked revisions found...");
		long count = 1;
		for (long i : linkedRevs) {

			//in case we needed to stop the process
			if(i <= lastRevisionProcessed){
				log.info("revision " + i + " was processed already!");
				continue;
			}

			List<SVNLogEntry> logEntries = new ArrayList<SVNLogEntry>();
			try {
				BackhoeLogEntryHandler handler = new BackhoeLogEntryHandler();
				encapsulation.log(new String[] { "" }, i, i, true, false, 0, true, null, handler);
				logEntries.addAll(handler.getEntries());
			} catch (SVNException svne) {
				svne.printStackTrace();
				continue;
			}
			SVNLogEntry entry = logEntries.get(0);
			logEntries.clear();
			//setting up per-revision information
			//this.gpho = new GraphDatabaseOperationsRdb();
			Map<SzzFileRevision,LinkedList<Line>> model = new HashMap<SzzFileRevision,LinkedList<Line>>();
			bicodes = new ArrayList<BugIntroducingCode>();
			//additions = new ArrayList<OnlyAdditionCode>();

			for (SVNLogEntryPath ep : entry.getChangedPaths().values()) {
				final String path = ep.getPath();
				final String type = ep.getKind().toString();
				if (!type.equals("file")) {
					continue;
				}
				//we are restricting to files
				String fname = getSvnFileName(path);
				if(fname == null){
					continue;
				}
				//we are restricting to java code
				if (!fname.contains(".java")) {
					continue;
				}

				final LinkedList<SVNFileRevision> fileRevisions = new LinkedList<SVNFileRevision>();
				final LinkedList<SzzFileRevision> szzFileRevisions = new LinkedList<SzzFileRevision>();
				try {
					BackhoeFileRevisionHandler handler = new BackhoeFileRevisionHandler();
					encapsulation.getFileRevisions(path,0L,entry.getRevision(),true,handler);
					fileRevisions.addAll(handler.getFileRevisions());
				} catch (SVNException e) {
					if (e.getMessage().contains("is not a file in revision") 
							|| e.getMessage().contains("Found malformed header")) {
						continue;
					} else {
						throw e;
					}
				}
				log.info("number of revision-files: " + fileRevisions.size());
				if(fileRevisions.size() == 1){
					continue;
				}

				convertFileRevisions(fileRevisions,szzFileRevisions);
				ByteArrayOutputStream baous = catOperation(repoUrl, ep.getPath(), entry.getRevision());
				
				//we are also not interested on testFiles
				if(isTestFile(baous)){
					log.info("test class found! skip it");
					log.info("class: " + path);
					continue;
				}

				Collections.sort(szzFileRevisions, revisionComp);
				buildLinesModel(model,szzFileRevisions);
				traceBack(model,szzFileRevisions,fname);
				fileRevisions.clear();
			}
			//List<NodeDb> nodesToPersist = gpho.getNodesToPersist();
			synchronized(liDao){
				Transaction tx = liDao.beginTransaction();
				//for(NodeDb nodeToPersist : nodesToPersist){
				//	liDao.insertNodeDb(nodeToPersist);
				//	for(String evolution : nodeToPersist.getEvolutions()){
				//		liDao.insertEvolution(evolution,nodeToPersist);
				//	}
				//}
				for(BugIntroducingCode bicode : bicodes){
					liDao.insertBugIntroducingCode(bicode);
				}
				liDao.updateProjectRevisionsProcessed(project, i);
				tx.commit();
			}
			//nodesToPersist.clear();
			//this.gpho = null;
			log.info(count + " processed revisions of " + linkedRevs.size() + " for project " + project);
			count++;
		}
		String sql = "insert into szzfinishedproject values (:param1,:param2)";
		Transaction tx = liDao.beginTransaction();
		liDao.executeSQLWithParams(sql, this.project,new Date());
		tx.commit();

		//SendMailSSL sendmail = new SendMailSSL("brain");
		//sendmail.sendMail(this.project + " have finished","check your results ;-)");

		return true;
	}

	public void buildLinesModel(Map<SzzFileRevision,LinkedList<Line>> model, LinkedList<SzzFileRevision> fileRevisions) throws Exception {

		for (final SzzFileRevision fr : fileRevisions) {
			if (fileRevisions.indexOf(fr) == (fileRevisions.size() - 1)) {
				break;
			}

			final SzzFileRevision nextFr = fileRevisions.get(fileRevisions.indexOf(fr) + 1);
			final ByteArrayOutputStream diff = diffOperation(repoUrl, fr, nextFr);
			final ByteArrayOutputStream frContent = catOperation(repoUrl, fr);
			final ByteArrayOutputStream nextFrContent = catOperation(repoUrl, nextFr);
			try{
				buildLinesModel(model, diff, frContent, nextFrContent, fr.getRevision(), nextFr.getRevision(), 
						fr.getPath(), nextFr.getPath(), fr, nextFr, project);
			} finally {
				diff.close();
				frContent.close();
				nextFrContent.close();
			}
		}
	}
	
	public void buildLinesModel(Map<SzzFileRevision,LinkedList<Line>> model, ByteArrayOutputStream diff, ByteArrayOutputStream frContent,
			ByteArrayOutputStream nextFrContent, long revision, long nextRevision, String previousPath, String nextPath,
			SzzFileRevision fr, SzzFileRevision nextFr, String project)
			throws Exception {
		List<String> headers = getHeaders(diff);
		List<DiffHunk> hunks = getHunks(model, diff, headers, previousPath, nextPath, fr, nextFr, revision, nextRevision);
		if (!hunks.isEmpty()) {
			//add the case where hunks are not empty but only comments are done
			if(!isPropertyChangeOnly(diff)){
				nextFr.setChangeproperty(true);
			}
			buildLinesBeforeAfterBetweenHunks(model,hunks, frContent, nextFrContent, revision, 
					nextRevision, previousPath, nextPath, fr, nextFr, project);
		} else {
			String fname = getSvnFileName(fr.getPath());
			//log.info(fr.getRevision() + "#" + fname + "# empty hunks!");
			verifyIsMergeOrBranching(nextFr,fr.getPath());
			model.put(fr,new LinkedList<Line>());//just adding a revision without any lines in the diff
		}
		headers.clear();
		hunks.clear();
	}

	public List<DiffHunk> getHunks(Map<SzzFileRevision,LinkedList<Line>> model, ByteArrayOutputStream diff, List<String> headers, 
			String previousPath, String nextPath, SzzFileRevision fr, SzzFileRevision nextFr,
			long revision, long nextRevision) throws IOException{
		List<DiffHunk> hunks = getDiffHunks(diff, headers, previousPath, nextPath, revision, nextRevision);
		joinUnfinishedLines(hunks);

		//updating my model map
		for(DiffHunk hunk : hunks){
			for(Line line : hunk.getContent()){
				LinkedList<Line> lines = model.get(fr);
				if(lines == null){
					lines = new LinkedList<Line>();
				}
				lines.add(line);
				model.put(fr, lines);
			}
		}
		return hunks;
	}

	private void buildLinesBeforeAfterBetweenHunks(Map<SzzFileRevision,LinkedList<Line>> model, List<DiffHunk> hunks, ByteArrayOutputStream frContent,
			ByteArrayOutputStream nextFrContent, long previousRevision, long revision, String previousPath, 
			String nextPath, SzzFileRevision fr, SzzFileRevision nextFr, String project) throws IOException {

		String fname = getSvnFileName(previousPath);
		BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(frContent.toByteArray())));
		LinkedList<Line> linesNotInHunk = model.get(fr);
		
		if(linesNotInHunk == null){
			linesNotInHunk = new LinkedList<Line>();
		}
		int lineNumber = 1;
		//getting the lines that are out of hunks.
		try{
			while (br.ready()) {
				String lineContent = br.readLine();
				Line line = null;
				for (DiffHunk hunk : hunks) {
					//because of this, we are going to put every +addition with line -1
					//which is useful in the traceBack method createLinesInPreviousRevisions
					line = hunk.isLinePreviousRevisionInvolved(lineNumber);
					if (line != null) {
						break;
					}
				}

				if (line == null) {
					Line lineobj = new Line();
					lineobj.setContent(lineContent);
					lineobj.setRevision(revision);
					lineobj.setPreviousRevision(previousRevision);
					lineobj.setPreviousPath(previousPath);
					lineobj.setNextPath(nextPath);
					lineobj.setPreviousNumber(lineNumber);
					linesNotInHunk.add(lineobj);
				}
				lineNumber++;
			}
		} finally {
			br.close();
			br = null;
		}

		for(Line currentLine : linesNotInHunk){ 

			if(currentLine.getFoundInDiffHunks()){
				continue;
			}

			Line lastLine = hunks.get(hunks.size()-1).getLastLine();
			Line firstLine = hunks.get(0).getFirstLine();

			int lastLineNumber = lastLine.getPreviousNumber();
			if(lastLineNumber == -1){ // in case the last line of the hunk was an addition
				lastLineNumber = lastLine.getNumber();
			}

			for (DiffHunk hunk : hunks) {
				int firstLineNextHunk = -1;
				if ((hunks.indexOf(hunk) + 1) != hunks.size()) { // if this is not
					// the last hunk
					firstLineNextHunk = hunks.get(hunks.indexOf(hunk) + 1).getFirstLine().getPreviousNumber();
					if (currentLine.getPreviousNumber() > hunk.getLastLine().getPreviousNumber()
							&& currentLine.getPreviousNumber() < firstLineNextHunk) {
						// in case the current line is in between the hunk and the next hunk
						int diffPosition = hunk.getLastLine().getNumber() - hunk.getLastLine().getPreviousNumber();
						currentLine.setNumber(currentLine.getPreviousNumber() + diffPosition);
					}
				}
			}

			//in case the Line is after all hunks
			if (currentLine.getPreviousNumber() > lastLineNumber) {
				if(lastLine.getPreviousNumber() != -1){
					int diffPosition = lastLine.getNumber() - lastLine.getPreviousNumber();
					currentLine.setNumber(currentLine.getPreviousNumber() + diffPosition);
				} else {
					currentLine.setNumber(currentLine.getPreviousNumber() + 
							lastLine.getContext_difference() + 
							(lastLine.getAdditions() - (lastLine.getDeletions()-1))
							);
				}

      			// modifications before the hunks, which means that they didnt change
			} else if (currentLine.getPreviousNumber() < firstLine.getPreviousNumber()) {
				// in case the Line is before all hunks
				currentLine.setNumber(currentLine.getPreviousNumber());
			}
			lineNumber++;
		}

		//because of this, we are going to put every +addition with line -1
		//which is useful in the traceBack method createLinesInPreviousRevisions
		for(Line isAddition : linesNotInHunk){
			if(isAddition.getType() == LineType.ADDITION){
				isAddition.setNumber(-1);
				isAddition.setPreviousNumber(-1);//don't think it is necessary but let's enforce it
			}
		}

		joinUnfinishedLinesWhenCloning(linesNotInHunk);
		model.put(fr,linesNotInHunk);
	}

	public void traceBack(Map<SzzFileRevision,LinkedList<Line>> model, 
			LinkedList<SzzFileRevision> fileRevisions, String fname) throws Exception {
		final SzzFileRevision fixRev = fileRevisions.getLast(); 
		final SzzFileRevision beforeRev = fileRevisions.get(fileRevisions.indexOf(fixRev)-1);
		final ByteArrayOutputStream diff = diffOperation(repoUrl, beforeRev, fixRev);
		List<DiffHunk> hunks = null;
		List<String> headers = null;
		//List<Line> localAdditions = new ArrayList<Line>();
		try{
			headers = getHeaders(diff);
			hunks = getDiffHunks(diff, headers, beforeRev.getPath(), fixRev.getPath(),
					beforeRev.getRevision(), fixRev.getRevision());	
			joinUnfinishedLines(hunks);
			boolean deletionFound = false;
			for(DiffHunk hunk : hunks){
				for(Line linetotrace : hunk.getContent()){
					if(linetotrace.getType() == LineType.DELETION){
						if(!isCommentOrBlankLine(linetotrace.getContent()) 
								&& !isImport(linetotrace.getContent())){               
							deletionFound = true;
							createLinesInPreviousRevisions(model,linetotrace, 
									beforeRev, fileRevisions, fname, fixRev);
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			diff.close();
		}
	}

	private OnlyAdditionCode createOnlyAdditionCode(Line addition, SzzFileRevision beforeRev, 
			SzzFileRevision fixRev){
		OnlyAdditionCode oacode = new OnlyAdditionCode();
		oacode.setPath(addition.getPreviousPath());
		oacode.setRevision(beforeRev.getRevision());
		oacode.setFixrevision(fixRev.getRevision());
		oacode.setCode(addition.getContent());
		String toparse = fixRev.getRevisionProperties()
			.getStringValue(SVNRevisionProperty.DATE);
		Date date = getRevisionDate(toparse);
		oacode.setDate(date);
		return oacode;
	}

	private void createLinesInPreviousRevisions(Map<SzzFileRevision, LinkedList<Line>> model, Line linetotrace,
			SzzFileRevision rev, LinkedList<SzzFileRevision> fileRevisions, String fname, SzzFileRevision fixRev){
		Line prevline = null;
		SzzFileRevision prevrev = getPrevRev(rev,fileRevisions);
		//if the buggycode is in rev from the start
		//we have to persist it when prevrev == null
		if(prevrev != null && model.get(prevrev) != null) {
			//log.info("prevrev: " + prevrev.getRevision());
			LinkedList<Line> previousLines = model.get(prevrev); 
			String content = prepareLineContent(linetotrace);
			//log.info("[" + linetotrace.getPreviousNumber() + "] line_content = " + content);

			boolean matchFound = false;
			for(Line line : previousLines){
				String prevcontent = prepareLineContent(line);
				//log.info("[" + line.getPreviousNumber() + "," + line.getNumber() + "] prev_line_content = " + prevcontent);

				if(content.equals(prevcontent)){
					if(line.getNumber() != -1){
						if(line.getNumber() == linetotrace.getPreviousNumber()){
							prevline = line; 
							if(prevline != null){
								matchFound = true;
								log.info(" found a match to [" + line.getNumber() + ",rev:" + prevrev.getRevision()
										+ "] prev_line_content = " + prevcontent);
								//recursive call to traceback
								createLinesInPreviousRevisions(model,prevline,prevrev,fileRevisions,
										fname, fixRev);
							}
								}
					} else if(linetotrace.getPreviousNumber() == (line.getPreviousNumber()
							 +line.getContext_difference() +line.getPosition())) {
						prevline = line;
						if(prevline != null){
							matchFound = true;
							log.info("found match by content and context adjustment in rev: " + prevrev.getRevision());
							createLinesInPreviousRevisions(model,prevline,prevrev,fileRevisions,
									fname, fixRev);
						}
					} else { //last match attempt
						List<Integer> additions = getAdditionsInHunk(line);
						if(!additions.isEmpty()){
							for(Integer addition : additions){
								int position = addition - line.getDeletions();
								if(linetotrace.getPreviousNumber() == (line.getPreviousNumber() + 
										line.getContext_difference() + position)){
									prevline = line;
									if(prevline != null){
										matchFound = true;
										log.info("found match by evolution trace!");
										createLinesInPreviousRevisions(model,prevline,prevrev,fileRevisions,
												fname, fixRev);
										break;
									}
       								}
							}
						} else {
							int position = 0;
							if(line.getContext_difference() > 0){
								position = line.getDeletions() - line.getAdditions();
							} else {
								position = line.getAdditions() - line.getDeletions();
							}

							if(linetotrace.getPreviousNumber() == (line.getPreviousNumber() + 
										line.getContext_difference() + position)){
								prevline = line;
								if(prevline != null){
									matchFound = true;
									log.info("found match by local evolution trace!");
									createLinesInPreviousRevisions(model,prevline,prevrev,fileRevisions,
											fname, fixRev);
									break;
								}
						       	}
						}
					} 
				}
			}
			if(!matchFound){
				createBicode(rev, fixRev, linetotrace);
			}
		} else {
			createBicode(rev,fixRev,linetotrace);
		}
	}

	public BugIntroducingCode createBicode(SzzFileRevision rev, SzzFileRevision fixRev, Line line){
		log.info(" create bug-intro-code: [r:" + rev.getRevision() 
				+ "," + line.getContent() + "]");
		BugIntroducingCode b = new BugIntroducingCode();
		b.setFixRevision(fixRev.getRevision());
		b.setContent(line.getContent());
		b.setLinenumber(line.getPreviousNumber());
		b.setPath(rev.getPath());
		b.setRevision(rev.getRevision());
		b.setProject(this.project);
		String toparse = rev.getRevisionProperties()
			.getStringValue(SVNRevisionProperty.DATE);
		Date creation = getRevisionDate(toparse);
		b.setSzzDate(creation);
		b.setMergerev(rev.getMergerev());
		b.setBranchrev(rev.getBranchrev());
		b.setChangeproperty(rev.getChangeproperty());
			
		bicodes.add(b);
		return b;
	}

	public void verifyIsMergeOrBranching(SzzFileRevision rev, String previousPath){
		SVNLogEntryPath entryPath = null;
		try{
			entryPath = getSvnLogEntryPath(encapsulation, rev.getRevision(),rev.getPath());
		} catch (SVNException svne){
			log.error("could not get SVNLogEntryPath for " + rev.getRevision() + " " + rev.getPath()); 
			log.error(svne.getMessage());
		}
		String fname = getSvnFileName(rev.getPath());
		if(entryPath != null){
			String copyPath = entryPath.getCopyPath();
			long copyRevision = entryPath.getCopyRevision();
			if(copyPath != null && copyPath.length() > 0){
				log.info(rev.getRevision() + "#" + fname + "# it is a copy revision!");
				rev.setBranchrev(true);
			} else if (!rev.getPath().equals(previousPath)) {
				log.info(rev.getRevision() + "#" + fname + "#  it is a merge revision!");
				rev.setMergerev(true);
			} else if (isPropertyChange) {
				log.info(rev.getRevision() + "#" + fname + "# it is only a property change!");
				rev.setChangeproperty(true);
				isPropertyChange = false;
			}
		} else {
			log.info(rev.getRevision() + "#" + fname + "# its a branch revision!");
			rev.setBranchrev(true);
		}
	}
}
