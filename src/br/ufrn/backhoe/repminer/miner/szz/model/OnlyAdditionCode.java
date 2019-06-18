package br.ufrn.backhoe.repminer.miner.szz.model;
import java.util.*;

public class OnlyAdditionCode {

	public String path;
	public String code;
	public long revision;
	public long fixrevision;
	public Date date;
	public String issuecode;

	/**
	 * Get path.
	 *
	 * @return path as String.
	 */
	public String getPath()
	{
	    return path;
	}

	/**
	 * Set path.
	 *
	 * @param path the value to set.
	 */
	public void setPath(String path)
	{
	    this.path = path;
	}

	/**
	 * Get code.
	 *
	 * @return code as String.
	 */
	public String getCode()
	{
	    return code;
	}

	/**
	 * Set code.
	 *
	 * @param code the value to set.
	 */
	public void setCode(String code)
	{
	    this.code = code;
	}

	/**
	 * Get revision.
	 *
	 * @return revision as long.
	 */
	public long getRevision()
	{
	    return revision;
	}

	/**
	 * Set revision.
	 *
	 * @param revision the value to set.
	 */
	public void setRevision(long revision)
	{
	    this.revision = revision;
	}

	/**
	 * Get fixrevision.
	 *
	 * @return fixrevision as long.
	 */
	public long getFixrevision()
	{
	    return fixrevision;
	}

	/**
	 * Set fixrevision.
	 *
	 * @param fixrevision the value to set.
	 */
	public void setFixrevision(long fixrevision)
	{
	    this.fixrevision = fixrevision;
	}

	/**
	 * Get date.
	 *
	 * @return date as Date.
	 */
	public Date getDate()
	{
	    return date;
	}

	/**
	 * Set date.
	 *
	 * @param date the value to set.
	 */
	public void setDate(Date date)
	{
	    this.date = date;
	}

	/**
	 * Get issuecode.
	 *
	 * @return issuecode as String.
	 */
	public String getIssuecode()
	{
	    return issuecode;
	}

	/**
	 * Set issuecode.
	 *
	 * @param issuecode the value to set.
	 */
	public void setIssuecode(String issuecode)
	{
	    this.issuecode = issuecode;
	}
}
