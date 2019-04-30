package br.ufrn.backhoe.repminer.miner.szz.model;

import org.tmatesoft.svn.core.io.*;
import org.tmatesoft.svn.core.*;

public class SzzFileRevision {
	private SVNFileRevision filerev;
	private boolean branchrev;
	private boolean mergerev;
	private boolean changeproperty;
	private boolean first;

	public SzzFileRevision(SVNFileRevision filerev){
		this.filerev = filerev;
		this.first = false;
	}

	/**
	 * Get branchrev.
	 *
	 * @return branchrev as boolean.
	 */
	public boolean getBranchrev()
	{
	    return branchrev;
	}

	/**
	 * Set branchrev.
	 *
	 * @param branchrev the value to set.
	 */
	public void setBranchrev(boolean branchrev)
	{
	    this.branchrev = branchrev;
	}

	/**
	 * Get mergerev.
	 *
	 * @return mergerev as boolean.
	 */
	public boolean getMergerev()
	{
	    return mergerev;
	}

	/**
	 * Set mergerev.
	 *
	 * @param mergerev the value to set.
	 */
	public void setMergerev(boolean mergerev)
	{
	    this.mergerev = mergerev;
	}

	/**
	 * wrapper method to return path
	 */
	public String getPath(){
		return filerev.getPath();
	}

	/**
	 * wrapper method to return revision
	 */
	public long getRevision(){
		return filerev.getRevision();
	}

	/**
	 * wrapper method to return revisionProperties
	 */
	public SVNProperties getRevisionProperties(){
		return filerev.getRevisionProperties();
	}

	/**
	 * Get changeproperty.
	 *
	 * @return changeproperty as boolean.
	 */
	public boolean getChangeproperty()
	{
	    return changeproperty;
	}

	/**
	 * Set changeproperty.
	 *
	 * @param changeproperty the value to set.
	 */
	public void setChangeproperty(boolean changeproperty)
	{
	    this.changeproperty = changeproperty;
	}

	/**
	 * Get first.
	 *
	 * @return first as boolean.
	 */
	public boolean getFirst()
	{
	    return first;
	}

	/**
	 * Set first.
	 *
	 * @param first the value to set.
	 */
	public void setFirst(boolean first)
	{
	    this.first = first;
	}
}
