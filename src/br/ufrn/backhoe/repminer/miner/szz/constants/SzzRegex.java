package br.ufrn.backhoe.repminer.miner.szz.constants;

public enum SzzRegex {

        EXTENDS_TESTCASE("(extends)(\\s+)(TestCase)"),
	TEST_ANNOTATION("(@Test)");

	private SzzRegex(String value){
		this.value = value;
	}

	private String value;

	public String getValue(){
		return this.value;
	}
}
