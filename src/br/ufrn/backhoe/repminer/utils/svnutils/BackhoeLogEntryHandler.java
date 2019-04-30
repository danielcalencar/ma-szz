package br.ufrn.backhoe.repminer.utils.svnutils;

import org.tmatesoft.svn.core.io.*;
import org.tmatesoft.svn.core.io.diff.*;
import org.tmatesoft.svn.core.*;
import java.util.*;

public class BackhoeLogEntryHandler implements ISVNLogEntryHandler {
	private List<SVNLogEntry> entries;

	public BackhoeLogEntryHandler(){
		entries = new ArrayList<SVNLogEntry>();
	}

	public void handleLogEntry(SVNLogEntry logEntry){
		entries.add(logEntry);
	}

	public List<SVNLogEntry> getEntries(){
		return entries;
	}

}
