package br.ufrn.backhoe.persistence;

import java.util.List;
import br.ufrn.backhoe.repminer.miner.szz.model.*;
import java.util.Date;

public abstract class LinkedIssueSvnDAO extends AbstractDAO {

	public abstract List<Long> getLinkedRevisions();

	public abstract List<Long> getLinkedRevisions(String project);
	
	public abstract List<Long> getLinkedRevisionWAffectedVersions();
	
        public abstract List<Long> getLinkedRevisionWAffectedVersions(String project);
	
        public abstract long getLastRevisionProcessed(String project);

        public abstract void insertProjectRevisionsProcessed(String project);

        public abstract void updateProjectRevisionsProcessed(String project, long revision);

	public abstract void insertBugIntroducingCode(BugIntroducingCode bicode);

}
