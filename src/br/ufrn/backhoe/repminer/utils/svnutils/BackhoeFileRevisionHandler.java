package br.ufrn.backhoe.repminer.utils.svnutils;

import org.tmatesoft.svn.core.io.*;
import org.tmatesoft.svn.core.io.diff.*;
import org.tmatesoft.svn.core.*;
import java.util.*;

public class BackhoeFileRevisionHandler implements ISVNFileRevisionHandler {
	private List<SVNFileRevision> fileRevisions;

	public BackhoeFileRevisionHandler(){
		fileRevisions = new ArrayList<SVNFileRevision>();
	}

	public void openRevision(SVNFileRevision fileRevision){
		fileRevisions.add(fileRevision);
	}

	public void closeRevision(String token){
		//TODO: nothing to do right now
	}

	public void applyTextDelta(java.lang.String path,
			java.lang.String baseChecksum)
		throws SVNException {
		//TODO: nothing to do right now
	}

	public java.io.OutputStream textDeltaChunk(java.lang.String path,
			SVNDiffWindow diffWindow)
		throws SVNException {
		//TODO: nothing to do right now
		return null;
	}

	public void textDeltaEnd(java.lang.String path)
		throws SVNException {
		//TODO: nothing to do right now
	}

	public List<SVNFileRevision> getFileRevisions(){
		return fileRevisions;
	}

}
