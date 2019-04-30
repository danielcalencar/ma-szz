package br.ufrn.backhoe.repminer.miner.szz.workers;

import static br.ufrn.backhoe.repminer.utils.svnutils.SvnOperationsUtil.*;
import br.ufrn.backhoe.repminer.utils.svnutils.*;

import br.ufrn.backhoe.persistence.*;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNFileRevision;
import org.tmatesoft.svn.core.*;
import org.tmatesoft.svn.core.wc2.SvnAnnotateItem;
import java.util.*;
import java.io.*;
import org.apache.log4j.*;
import br.ufrn.backhoe.repminer.miner.szz.model.*;
import org.hibernate.Transaction;

public class FindBugIntroducingChangesSliwerski {
	private static final Logger log = Logger
		.getLogger(FindBugIntroducingChangesSliwerski.class);
	private String project;
	private LinkedIssueSvnDAO lidao;
	private SVNRepository encap;
	private boolean entireDb;
	private String repoUrl;
	private Comparator<SzzFileRevision> revisioncomp = new Comparator<SzzFileRevision>() {
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

	public FindBugIntroducingChangesSliwerski(String project, LinkedIssueSvnDAO lidao, 
			SVNRepository encap, boolean entireDb, String repoUrl){
		this.project = project;
		this.lidao = lidao;
		this.encap = encap;
		this.entireDb = entireDb;
		this.repoUrl = repoUrl;
	}

	public void run(){
		long lastRevisionProcessed = lidao.getLastRevisionProcessed(project);
		if(lastRevisionProcessed == 0L){
			lidao.insertProjectRevisionsProcessed(project);
		}

		//getting the revisions to process.
		List<Long> linkedRevs = null;
		if(entireDb){
			linkedRevs = lidao.getLinkedRevisions(project); 
		} else {
			linkedRevs = lidao.getLinkedRevisionWAffectedVersions(project);
		}
		log.info("Project " + project + " starting...");                 
		log.info(linkedRevs.size() + " Linked revisions found...");
		long logcount = 1;

		try{
			for(Long rev : linkedRevs){
				//in case we needed to stop the process
				if(rev <= lastRevisionProcessed){
					log.info("revision " + rev + " was processed already!");
					continue;
				}

				List<SVNLogEntry> logEntries = new ArrayList<SVNLogEntry>();
				try {
					encap.log(new String[] { "" }, logEntries, rev, rev, true, false);
				} catch (SVNException svne) {
					svne.printStackTrace();
					continue;
				}
				SVNLogEntry entry = logEntries.get(0);
				logEntries.clear();

				List<BugIntroducingCode> bugchanges = new ArrayList<BugIntroducingCode>();
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
						encap.getFileRevisions(path, fileRevisions, 0L, entry.getRevision());
					} catch (SVNException e) {
						if (e.getMessage().contains("is not a file in revision")) {
							continue;
						} else {
							log.error("error trying to get file revisions \n" 
									+ e.getMessage());
						}
					}
					log.info("number of revision-files: " + fileRevisions.size());

					if(fileRevisions.size() == 1){
						continue;
					}

					ByteArrayOutputStream baous = catOperation(repoUrl, ep.getPath(), entry.getRevision());
					//we are also not interested on testFiles
					if(isTestFile(baous)){
						log.info("test class found! skip it");
						log.info("class: " + path);
						continue;
					}

					convertFileRevisions(fileRevisions,szzFileRevisions);
					Collections.sort(szzFileRevisions, revisioncomp);
					findBugIntroducingChanges(bugchanges,szzFileRevisions);
				}
				Transaction tx = lidao.beginTransaction();
				for(BugIntroducingCode bugchange : bugchanges){
					log.info("szz_date: " + bugchange.getSzzDate());
					log.info("szz_date class " + bugchange.getSzzDate().getClass());
					lidao.insertBugIntroducingCode(bugchange);
				}
				lidao.updateProjectRevisionsProcessed(this.project,rev);
				tx.commit();
				log.info(logcount + " revisions processed of " + linkedRevs.size());
				logcount++;
			}

		} catch (Exception e){
			log.error("IO error \n" + e.getMessage());
			e.printStackTrace();
		}
	}

	private void findBugIntroducingChanges(List<BugIntroducingCode> bugchanges,
			List<SzzFileRevision> szzFileRevisions) throws IOException, SVNException {
		SzzFileRevision fixRevision =  szzFileRevisions.get(szzFileRevisions.size()-1);
		SzzFileRevision beforeRevision = szzFileRevisions.get(szzFileRevisions.size()-2);
		ByteArrayOutputStream diffout = diffOperation(repoUrl,beforeRevision,fixRevision);
		List<String> headers = getHeaders(diffout); 
		List<DiffHunk> hunks = getDiffHunks(diffout, headers, beforeRevision.getPath(),
				fixRevision.getPath(), beforeRevision.getRevision(), 
				fixRevision.getRevision());
		List<SvnAnnotateItem> annotations = annotateOperation(repoUrl, beforeRevision);
		for(SvnAnnotateItem annotation : annotations){
			for(DiffHunk hunk : hunks){
				List<Line> contents = hunk.getContent();
				for(Line content : contents){
					if(content.getType() == LineType.DELETION && 
							content.getPreviousNumber() == annotation.getLineNumber()){
						log.info("bug-intro change found for revision " + fixRevision.getRevision());
						log.info("Line: " + content.getPreviousNumber() + " " + content.getContent());
						BugIntroducingCode bugchange = createBugChange(annotation,szzFileRevisions,
								fixRevision.getRevision());
						if(szzFileRevisions.size() >= 3){
							bugchange = isMetaChange(bugchange,szzFileRevisions);
						}
						bugchanges.add(bugchange);
					}
				}
			}
		}
	}

	public BugIntroducingCode createBugChange(SvnAnnotateItem blame, List<SzzFileRevision> revisions, 
			long fixrevision) throws SVNException {
		BugIntroducingCode bugchange = new BugIntroducingCode();
		bugchange.setLinenumber(blame.getLineNumber());
		bugchange.setContent(blame.getLine());
		bugchange.setRevision(blame.getRevision());
		bugchange.setFixRevision(fixrevision);
		bugchange.setProject(this.project);
		bugchange.setSzzDate(new Date(blame.getDate().getTime()));

		for(SzzFileRevision rev : revisions){
			if(rev.getRevision() == bugchange.getRevision()){
				bugchange.setPath(rev.getPath());
			}
		}

		if(bugchange.getPath() == null) {
			log.info("Path null to revision: " + bugchange.getRevision());
		}

		//trying to find the path

		//SVNLogEntryPath ep = getSvnLogEntryPathBasedOnName(encap,blame.getRevision(),
		//		line.getPreviousPath());
		//if(ep == null){
		//	log.info("null path for prevrev. It may be a branch revision!");
		//} else {
		//	bugchange.setPath(ep.getPath());
		//}

		return bugchange;
	}

	public BugIntroducingCode isMetaChange(BugIntroducingCode bugchange, 
			List<SzzFileRevision> szzFileRevisions) throws IOException {
		for(SzzFileRevision rev : szzFileRevisions){
			if(rev.getRevision() == bugchange.getRevision()){
				int index = szzFileRevisions.indexOf(rev);
				if(index == 0){
					return bugchange;
				}
				SzzFileRevision prevrev = szzFileRevisions.get(index-1);
				SzzFileRevision bugchangez = new SzzFileRevision(
						new SVNFileRevision(bugchange.getPath(),bugchange.getRevision(),
							null,null));
				log.info("prevrev: " + prevrev.getRevision());
				log.info("prevrev_path: " + prevrev.getPath());
				log.info("rev: " + bugchange.getRevision());
				log.info("rev_path: " + bugchange.getPath());
				//verify if there are hunks between the blamed revision and the 
				//previous revision
				ByteArrayOutputStream diff = new ByteArrayOutputStream();
				diff = diffOperation(repoUrl,prevrev,bugchangez);
				List<String> headers = getHeaders(diff);
				List<DiffHunk> hunks = getDiffHunks(diff,headers,prevrev.getPath(),
					bugchangez.getPath(),prevrev.getRevision(),
					bugchangez.getRevision());
				if(hunks.isEmpty()){
					log.info(bugchange.getRevision()+"#META-CHANGE FOUND ###########################################");
					verifyIsMergeOrBranching(bugchangez,prevrev.getPath());
					bugchange.setBranchrev(bugchangez.getBranchrev());
					bugchange.setMergerev(bugchangez.getMergerev());
					bugchange.setChangeproperty(bugchangez.getChangeproperty());
				}
			}
		}
		return bugchange;
	}

	public void verifyIsMergeOrBranching(SzzFileRevision rev, String previousPath){
		SVNLogEntryPath entryPath = null;
		try{
			entryPath = getSvnLogEntryPath(encap, rev.getRevision(),rev.getPath());
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

	public void convertFileRevisions(LinkedList<SVNFileRevision> fileRevisions, 
			LinkedList<SzzFileRevision> szzFileRevisions){
		for(SVNFileRevision svnfr : fileRevisions){
			SzzFileRevision szzfr = new SzzFileRevision(svnfr);
			szzFileRevisions.add(szzfr);
		}
	}
}
