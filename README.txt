
* English

1. This SZZ implementation consumes the data from the 'linkedissuessvn' table and
output the generated data into the 'bugintroducingcode' table. 

2. Since this implementation uses the Hibernate framework, the configuration
of the database connection can be found in the hibernate1.cfg.xml file. 

3. The implementation also looks at the backhoe.properties file to read some
parameters (for example, the url of the apache repository)

4. This implementation was not built using an specific IDE (i.e., only pure
java coding). I am providing a makefile and a MANIFEST.MF file in the
~/bin/classes directory, so that you can build your jar and run the szz from
the jar as well.

5. Commands:

B-SZZ:  br.ufrn.backhoe.repminer.miner.szz.SzzSliwerski 
AG-SZZ: br.ufrn.backhoe.repminer.miner.szz.SzzRdbNoBranch 
MA-SZZ: br.ufrn.backhoe.repminer.miner.szz.SzzRdb 

java -cp szz-package/bin/classes/;szz-package/bin/classes/szz_lib/* br.ufrn.backhoe.repminer.miner.szz.SzzSliwerski <project> (Windows)
java -cp szz-package/bin/classes/:szz-package/bin/classes/szz_lib/* br.ufrn.backhoe.repminer.miner.szz.SzzSliwerski <project> (Linux)

* Portuguese:

1. Preparando o banco de dados.

* Para que a implementação SZZ funcione é preciso que a tabela 'linkedissuessvn'
esteja populada. O SZZ também precisa das tabelas 'issuecontents' e
'issuecontents_affectedversions' que contém mais informações sobre as
linkedissues. Os sqls de exemplo para popular as tabelas estão na pasta ./sql.

* Essa implementação foi rodada usando o banco de dados postgres. Como essa
implementação usa o framework Hibernate, as configurações do banco de dados
devem ser feitas no arquivo hibernate1.cfg.xml (e.g., url do banco, usuario e
senha). 

2. Rodando o código.

* Para executar o SZZ é preciso rodar a classe br.ufrn.backhoe.repminer.miner.SzzRdb

* O SZZ irá gerar as bug-introducing-changes na tabela 'bugintroducingcode'

3. Commandos:

B-SZZ:  br.ufrn.backhoe.repminer.miner.szz.SzzSliwerski 
AG-SZZ: br.ufrn.backhoe.repminer.miner.szz.SzzRdbNoBranch 
MA-SZZ: br.ufrn.backhoe.repminer.miner.szz.SzzRdb 

java -cp szz-package/bin/classes/;szz-package/bin/classes/szz_lib/* br.ufrn.backhoe.repminer.miner.szz.SzzSliwerski <projecto> (Windows)
java -cp szz-package/bin/classes/:szz-package/bin/classes/szz_lib/* br.ufrn.backhoe.repminer.miner.szz.SzzSliwerski <projecto> (Linux)




