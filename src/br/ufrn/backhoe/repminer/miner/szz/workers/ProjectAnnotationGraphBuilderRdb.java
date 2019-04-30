package br.ufrn.backhoe.repminer.miner.szz.workers;

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
import static br.ufrn.backhoe.repminer.utils.svnutils.SvnOperationsUtil.*;
import br.ufrn.backhoe.repminer.utils.svnutils.*;

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
import java.util.concurrent.Future;

import java.io.File;
import java.io.*;

public class ProjectAnnotationGraphBuilderRdb implements Runnable {

	private static final Logger log = Logger.getLogger(ProjectAnnotationGraphBuilderRdb.class);
	private static LinkedIssueSvnDAO liDao;
	private SVNRepository encapsulation;
	private Label label;
	private String project;
	private String repoUrl;
	private String gphpath;
	private boolean entireDb = false;
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

	public ProjectAnnotationGraphBuilderRdb(SVNRepository encapsulation, LinkedIssueSvnDAO liDao,
			String project, String repoUrl, boolean entireDb ) {
		this.encapsulation = encapsulation;
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
			log.info(Thread.currentThread().getName() + "-" + project + " is running.");
			long startTime = System.nanoTime();
			buildAnnotationGraph();
			long endTime = System.nanoTime();
			c.readLine("duration: " + (endTime - startTime));
		} catch (Exception e) {
			e.printStackTrace();
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
		}
		log.info("Project " + project + " starting...");
		log.info(linkedRevs.size() + " Linked revisions found...");
		long count = 1;
		for (long i : linkedRevs) {


			List<SVNLogEntry> logEntries = new ArrayList<SVNLogEntry>();
			try {
				encapsulation.log(new String[] { "" }, logEntries, i, i, true, false);
			} catch (SVNException svne) {
				svne.printStackTrace();
				continue;
			}

			SVNLogEntry entry = logEntries.get(0);
			logEntries.clear();
			Map<SzzFileRevision,LinkedList<Line>> model = new HashMap<SzzFileRevision,LinkedList<Line>>();
			bicodes = new ArrayList<BugIntroducingCode>();

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
					encapsulation.getFileRevisions(path, fileRevisions, 0L, entry.getRevision());
				} catch (SVNException e) {
					if (e.getMessage().contains("is not a file in revision")) {
						continue;
					} else {
						throw e;
					}
				}

				if(fileRevisions.size() == 1){
					continue;
				}

				convertFileRevisions(fileRevisions,szzFileRevisions);
				ByteArrayOutputStream baous = catOperation(repoUrl, ep.getPath(), entry.getRevision());
				
				//we are also not interested on testFiles
				if(runRegex(path,"Test.java$")){
					log.info("test class found! skip it");
					log.info("class: " + path);
					continue;
				}
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
				for(BugIntroducingCode bicode : bicodes){
					liDao.insertBugIntroducingCode(bicode);
				}
				liDao.updateProjectRevisionsProcessed(project, i);
				tx.commit();
			}
			log.info(count + " processed revisions of " + linkedRevs.size() + " for project " + project);
			count++;
		}
		String sql = "insert into szzfinishedproject values (:param1,:param2)";
		Transaction tx = liDao.beginTransaction();
		liDao.executeSQLWithParams(sql, this.project,new Date());
		tx.commit();

		return true;
	}

	private void buildLinesModel(Map<SzzFileRevision,LinkedList<Line>> model, LinkedList<SzzFileRevision> fileRevisions) throws Exception {

		for (final SzzFileRevision fr : fileRevisions) {

			if (fileRevisions.indexOf(fr) == (fileRevisions.size() - 1)) {
				break;
			}

			final ByteArrayOutputStream frContent = catOperation(repoUrl, fr);
			final SzzFileRevision nextFr = fileRevisions.get(fileRevisions.indexOf(fr) + 1);
			final ByteArrayOutputStream diff = diffOperation(repoUrl, fr, nextFr);

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
	
        private String getSvnFileName(String path){
		if(path == null)
			return null;
		String[] tokens = path.split("/");
		if(tokens.length == 0)
			return null;
		String lastPart = tokens[tokens.length - 1];
		return lastPart;
	}

	private void buildLinesModel(Map<SzzFileRevision,LinkedList<Line>> model, ByteArrayOutputStream diff, ByteArrayOutputStream frContent,
			ByteArrayOutputStream nextFrContent, long revision, long nextRevision, String previousPath, String nextPath,
			SzzFileRevision fr, SzzFileRevision nextFr, String project)
			throws Exception {
		List<String> headers = getHeaders(diff);
		List<DiffHunk> hunks = getHunks(model, diff, headers, previousPath, nextPath, fr, nextFr, revision, nextRevision);

		if (!hunks.isEmpty()) {
			//c.readLine("is property Change only?!");
			if(!isPropertyChangeOnly(diff)){
				buildLinesBeforeAfterBetweeHunks(model,hunks, frContent, nextFrContent, revision, 
						nextRevision, previousPath, nextPath, fr, nextFr, project);
			} else {
				//c.readLine("yes! only property changes!");
				cloneLinesToPreviousRevision(model, frContent, nextFrContent, revision,
						nextRevision, previousPath, nextPath, fr, nextFr, project);
			}
		} else {
			cloneLinesToPreviousRevision(model,frContent, nextFrContent, revision, 
					nextRevision, previousPath, nextPath, fr, nextFr, project);
		}

		headers.clear();
		hunks.clear();
	}


	private void buildLinesBeforeAfterBetweeHunks(Map<SzzFileRevision,LinkedList<Line>> model, List<DiffHunk> hunks, ByteArrayOutputStream frContent,
			ByteArrayOutputStream nextFrContent, long previousRevision, long revision, String previousPath, 
			String nextPath, SzzFileRevision fr, SzzFileRevision nextFr, String project) throws IOException {

		String fname = getSvnFileName(previousPath);

		ByteArrayInputStream baous1 = new ByteArrayInputStream(frContent.toByteArray());
		InputStreamReader isr1 = new InputStreamReader(baous1);
		BufferedReader br = new BufferedReader(isr1);
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
			isr1 = null;
			baous1 = null;
		}

		for(Line currentLine : linesNotInHunk){ 

			if(currentLine.getFoundInDiffHunks()){
				continue;
			}

			//c.readLine("currentLine: " + currentLine.getContent());
			//c.readLine("previousNumber: " + currentLine.getPreviousNumber());
			//c.readLine("number: " + currentLine.getNumber());

			Line lastLine = hunks.get(hunks.size()-1).getLastLine();
			Line firstLine = hunks.get(0).getFirstLine();

			int lastLineNumber = lastLine.getPreviousNumber();
			if(lastLineNumber == -1){ // in case the last line of the hunk was an addition
				lastLineNumber = lastLine.getNumber();
			}

			//c.readLine("lastLine " + lastLine.getPreviousNumber());
			//c.readLine("lastLine2 " + lastLine.getNumber());
			//c.readLine("firstLine " + firstLine.getPreviousNumber());

			for (DiffHunk hunk : hunks) {
				int firstLineNextHunk = -1;
				if ((hunks.indexOf(hunk) + 1) != hunks.size()) { // if this is not
					// the last hunk
					firstLineNextHunk = hunks.get(hunks.indexOf(hunk) + 1).getFirstLine().getPreviousNumber();
					if (currentLine.getPreviousNumber() > hunk.getLastLine().getPreviousNumber()
							&& currentLine.getPreviousNumber() < firstLineNextHunk) {
						// in case the current line is in between the hunk and the next hunk
						//c.readLine("this line is in between hunks");
						int diffPosition = hunk.getLastLine().getNumber() - hunk.getLastLine().getPreviousNumber();
						currentLine.setNumber(currentLine.getPreviousNumber() + diffPosition);
					}
				}
			}

			//in case the Line is after all hunks
			if (currentLine.getPreviousNumber() > lastLineNumber) {
				//c.readLine("this line is after all hunks");
				if(lastLine.getPreviousNumber() != -1){
					int diffPosition = lastLine.getNumber() - lastLine.getPreviousNumber();
					currentLine.setNumber(currentLine.getPreviousNumber() + diffPosition);
				} else {
					//c.readLine("ops! should not be happening!!!");
					//c.readLine(currentLine.getContent());
					//c.readLine("path: " + currentLine.getPreviousPath());
					//c.readLine("revision " + revision + " previous_revision: " + previousRevision);
					//c.readLine("previousNumber = " + currentLine.getPreviousNumber());
					//c.readLine("context diff: " + lastLine.getContext_difference());
					//c.readLine("additions so far:" + lastLine.getAdditions() );
					//c.readLine("deletions so far:" + lastLine.getDeletions() + " (last)");
					currentLine.setNumber(currentLine.getPreviousNumber() + 
							lastLine.getContext_difference() + 
							(lastLine.getAdditions() - (lastLine.getDeletions()-1))
							);
					//c.readLine("new number! " + currentLine.getNumber());
				}

      			// modifications before the hunks, which means that they didnt change
			} else if (currentLine.getPreviousNumber() < firstLine.getPreviousNumber()) {
				//c.readLine("this line is before all hunks");
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

	private void cloneLinesToPreviousRevision(Map<SzzFileRevision,LinkedList<Line>> model, ByteArrayOutputStream frContent,
			ByteArrayOutputStream nextFrContent, long previousRevision, long revision, String previousPath,
			String nextPath, SzzFileRevision fr, SzzFileRevision nextFr, String project) throws IOException {
		BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(frContent.toByteArray())));
		LinkedList<Line> lines = model.get(fr);
		log.info("cloning revision: " + revision + " to " + previousRevision);
		if(lines == null){
			lines = new LinkedList<Line>();
		}
		int lineNumber = 1;
		try{
			while (br.ready()) {
				String lineContent = br.readLine();
				final Line lineNotInHunk = new Line();
				lineNotInHunk.setContent(lineContent);
				lineNotInHunk.setPreviousNumber(lineNumber);
				lineNotInHunk.setPreviousRevision(previousRevision);
				lineNotInHunk.setRevision(revision);
				lineNotInHunk.setNumber(lineNumber);
				lines.add(lineNotInHunk);
				lineNumber++;
			}
		} finally {
			br.close();
		}

		joinUnfinishedLinesWhenCloning(lines);
		model.put(fr,lines);
		//log.info("no diff between " + previousPath + " and " + nextPath);
	}

	private void traceBack(Map<SzzFileRevision,LinkedList<Line>> model, 
			LinkedList<SzzFileRevision> fileRevisions, String fname) throws Exception {
		final SzzFileRevision fixRev = fileRevisions.getLast(); 
		final SzzFileRevision beforeRev = fileRevisions.get(fileRevisions.indexOf(fixRev)-1);
		final ByteArrayOutputStream diff = diffOperation(repoUrl, beforeRev, fixRev);
		//log.info(""+diff);
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
						//#debug
						//String response = "";
						//response = c.readLine("line: " + linetotrace.getContent() + "#" 
						//		+ linetotrace.getPreviousNumber() + "\n skip?");
						//if(response.length() == 0){
						//	continue;
						//}
						//NodeDb node = createNode(linetotrace, fname);
						if(!isCommentOrBlankLine(linetotrace.getContent()) 
								&& !isImport(linetotrace.getContent())){               
							deletionFound = true;
							createLinesInPreviousRevisions(model,linetotrace, //node, 
									beforeRev, fileRevisions, fname, fixRev);
						}
					//} else if (linetotrace.getType() == LineType.ADDITION){
					//	localAdditions.add(linetotrace);
					}
				}
			}

			//if(!deletionFound){
			//	log.info("only addition diff found!");
			//	for(Line addition : localAdditions){
			//		OnlyAdditionCode oacode = createOnlyAdditionCode(addition,beforeRev,fixRev);
			//		this.additions.add(oacode);
			//	}
			//}
		} catch (Exception e) {
			e.printStackTrace();
			c.readLine("ops!");
		} finally {
			diff.close();
		}
	}

	//private OnlyAdditionCode createOnlyAdditionCode(Line addition, SVNFileRevision beforeRev, 
	//		SVNFileRevision fixRev){
	//	OnlyAdditionCode oacode = new OnlyAdditionCode();
	//	oacode.setPath(addition.getPreviousPath());
	//	oacode.setRevision(beforeRev.getRevision());
	//	oacode.setFixrevision(fixRev.getRevision());
	//	oacode.setCode(addition.getContent());
	//	String toparse = fixRev.getRevisionProperties()
	//		.getStringValue(SVNRevisionProperty.DATE);
	//	Date date = getRevisionDate(toparse);
	//	oacode.setDate(date);
	//	return oacode;

	//}

	private SzzFileRevision getPrevRev(SzzFileRevision fileRevision, List<SzzFileRevision> revisions){
		SzzFileRevision prev = null;
		int index = revisions.indexOf(fileRevision);
		//log.info("index " + index);
		//in case the file is not  the first of the collection
		if(index > 0){
			//log.info("prev index " + (index-1));
			prev = revisions.get(index-1);
		}
		return prev;
	}

	private void createLinesInPreviousRevisions(Map<SzzFileRevision, LinkedList<Line>> model, Line linetotrace, //NodeDb node, 
			SzzFileRevision rev, LinkedList<SzzFileRevision> fileRevisions, String fname, SzzFileRevision fixRev){
		Line prevline = null;
		//#debug
		//c.readLine("tracing rev: " + rev.getRevision());

		SzzFileRevision prevrev = getPrevRev(rev,fileRevisions);
		//#debug
		//c.readLine("prev rev: " + prevrev.getRevision());
		//if the buggycode is in rev from the start
		//we have to persist it when prevrev == null
		if(prevrev != null) {
			LinkedList<Line> previousLines = model.get(prevrev); 
			String content = prepareLineContent(linetotrace);
			//#debug
			//c.readLine("content to trace: " + content + " diff_context " + linetotrace.getContext_difference() + " prevrev: " + prevrev.getRevision());
			//String response = c.readLine("trace?");

			boolean matchFound = false;
			for(Line line : previousLines){
				String prevcontent = prepareLineContent(line);

				//#debug
				//if(response.length() != 0){
				//	c.readLine("line to trace: " + content + "#" + linetotrace.getPreviousNumber());
				//	c.readLine("prevline: " + prevcontent + "#" + line.getNumber() + "first: " + prevrev.getFirst()); 
				//	c.readLine("previousNumber: " + line.getPreviousNumber() + " contextdiff: " 
				//			+ line.getContext_difference() + " position: " + line.getPosition()
				//			+ "\n additions: " + line.getAdditions() + " deletions: " + line.getDeletions() 
				//			+ "\n additions2: " + linetotrace.getAdditions() 
				//			+ "\n cachedLine: " + line.getCachedNumber());
				//}
				
				if(content.equals(prevcontent)){

					//#debug
					//c.readLine("line to trace: " + content + "#" + linetotrace.getPreviousNumber());
					//c.readLine("prevline: " + prevcontent + "#" + line.getNumber()); 
					//c.readLine("previousNumber: " + line.getPreviousNumber() + " contextdiff: " 
					//		+ line.getContext_difference() + " position: " + line.getPosition()
					//		+ "\n additions: " + line.getAdditions() + " deletions: " + line.getDeletions() 
					//		+ "\n additions2: " + linetotrace.getAdditions() 
					//		+ "\n cachedLine: " + line.getCachedNumber());




					//log.info("[" + line.getPreviousNumber() + "," + line.getNumber() + "] prev_line_content = " + prevcontent);
					if(line.getNumber() != -1){
						//we have to find where the exact code was introduced
						//
						//this is why we don't care about evolutions array
						//because it means that the code have changed from
						//the previous revision
						if(line.getNumber() == linetotrace.getPreviousNumber()){
							prevline = line; 
							if(prevline != null){
								matchFound = true;
								log.info(" found a match to [" + line.getNumber() + ",rev:" + prevrev.getRevision()
										+ "] prev_line_content = " + prevcontent);
								//NodeDb prevnode = createNodeAddRel(prevline,node,fname);
								//recursive call to traceback
								createLinesInPreviousRevisions(model,prevline,prevrev,fileRevisions,
										fname, fixRev);
							}
						} 
					} else if( linetotrace.getPreviousNumber() == (line.getPreviousNumber()
								+line.getContext_difference() + line.getPosition())) {
						prevline = line;
						if(prevline != null){
							matchFound = true;
							//c.readLine("found match by content and context adjustment!");
							log.info("found match by content and context adjustment in rev: " + prevrev.getRevision());
							//recursive call to traceback
							createLinesInPreviousRevisions(model,prevline,prevrev,fileRevisions,
									fname, fixRev);
						}
					} else { //last match attempt
						//#debug
						//c.readLine("last match attempt... trying to do evolution tracing");
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
							//#debug
							//c.readLine("trying local evolution trace!");
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

	private BugIntroducingCode createBicode(SzzFileRevision rev, SzzFileRevision fixRev, Line line){
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
		bicodes.add(b);
		return b;
	}

	//private NodeDb createNodeAddRel(Line prline, NodeDb node, String fname){
	//	NodeDb prnode = this.gpho.createLineNode(prline.getPreviousPath(),prline.getPreviousNumber(),
	//				prline.getPreviousRevision(), prline.getContent(), this.project, fname);
	//	//if(!linetotrace.getEvolutions().isEmpty()){
	//	//	for(Line evol : linetotrace.getEvolutions()){
	//	//		NodeDb evolnode = this.gpho.createLineNode(evol.getNextPath(), evol.getNumber(),
	//	//				evol.getRevision(), evol.getContent(), this.project, fname);	
	//	//		this.gpho.createEvolvingRelationship(node,evolnode);
	//	//	}
	//	//}
	//	this.gpho.createEvolvingRelationship(prnode, node);
	//	return prnode;
	//}

	//private NodeDb createNode(Line line, String fname){
	//	String id = fname + "#" + line.getPreviousRevision() + "#" + line.getPreviousNumber(); 
	//	NodeDb nodeDb = this.gpho.createLineNode(line.getPreviousPath(), line.getPreviousNumber(),
	//			line.getPreviousRevision(), line.getContent(), this.project, fname);
	//	return nodeDb;
	//}
	

	private List<DiffHunk> getHunks(Map<SzzFileRevision,LinkedList<Line>> model, ByteArrayOutputStream diff, List<String> headers, 
			String previousPath, String nextPath, SzzFileRevision fr, SzzFileRevision nextFr,
			long revision, long nextRevision) throws IOException{
		List<DiffHunk> hunks = getDiffHunks(diff, headers, previousPath, nextPath, revision, nextRevision);
		joinUnfinishedLines(hunks);

		//updating my model map
		for(DiffHunk hunk : hunks){
			//#debug
			//String response = "";
			//response = c.readLine("header: " + hunk.getHeader() + " skip?");
			//if(response.length() == 0){
			//	continue;
			//}

			for(Line line : hunk.getContent()){
				//#debug
				//c.readLine("type: " + line.getType());
				//c.readLine("content: " + line.getContent());
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
}
