
testRunner.add(new TestCase({
  name: "testmod-uri-jdbc-derby",

  init: function() {
    java.lang.Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();

    this.db = new URI("jdbc:derby:memory:testmod-uri-jdbc;create=true");

    // Test server connectivity
    this.db.query("values 1");
  },

  setUp: function() {
    this.db.query("create table test (id bigint generated by default as identity, " +
				      "string varchar(20), number int)");
  },

  tearDown: function() {
    this.db.query("drop table test");
  },

  testQueryInsertJS: function() {
    let one = this.db.query("insert into test (string, number) values ({0}, {1})",
			    ["one", 1]);
    let id1 = this.db.query("values IDENTITY_VAL_LOCAL()");

    let two = this.db.query("insert into test values "
			    + "(default, {s1}, {n1}), (default, {s2}, {n2})",
			    { s1: "two", n1: 2,
			      s2: "three", n2: 3,
			      $result: "res" });
    // Inserts with multiple rows does not affect IDENTITY_VAL_LOCAL
    this.db.query("insert into test (string, number) values ({0}, {1})",
		  ["four", -4]);
    let id4 = this.db.query("values IDENTITY_VAL_LOCAL()", { $entry: "ent" });

    Assert.that(id1.entry.length() == 1, "INSERT did not generate one single entry")
    Assert.that(id1.._1.length() == 1, "INSERT did not generate one single identity")

    Assert.areEqual(one.@updateCount, 1, "updateCount is not 1");
    Assert.areEqual(id1.entry._1, 1, "IDENTITY of first INSERT was not 1");

    Assert.areEqual(two.@updateCount, 2, "updateCount is not 2");
    Assert.areEqual(two.localName(), "res", "result element name is not 'res'");

    Assert.areEqual(id4.ent._1, 4, "IDENTITY of third INSERT was not 4");
  },

  testQueryInsertXML: function() {
    let one = this.db.query("insert into test (string, number) values ({0}, {1})",
			    <><e>one</e><e>1</e></>);
    let id1 = this.db.query("values IDENTITY_VAL_LOCAL()");

    let two = this.db.query("insert into test values "
			    + "(default, {s1}, {n1}), (default, {s2}, {n2})",
			    <elem>
			    <s1>two</s1>   <n1>2</n1>
			    <s2>three</s2> <n2>3</n2>
			    </elem>
			    );

    // Inserts with multiple rows does not affect IDENTITY_VAL_LOCAL
    this.db.query("insert into test (string, number) values ({0}, {1})",
		  <> <c>four</c> <c>-4</c> </>);
    let id4 = this.db.query("values IDENTITY_VAL_LOCAL()");

    Assert.that(id1.entry.length() == 1, "INSERT did not generate one single entry")
    Assert.that(id1.._1.length() == 1, "INSERT did not generate one single identity")

    Assert.areEqual(one.@updateCount, 1, "updateCount is not 1");
    Assert.areEqual(id1.entry._1, 1, "IDENTITY of first INSERT was not 1");

    Assert.areEqual(two.@updateCount, 2, "updateCount is not 2");

    Assert.areEqual(id4.entry._1, 4, "IDENTITY of second INSERT was not 4");
  },

  testQuerySelect: function() {
    this.db.query("insert into test values (default, 'one', 1)");
    this.db.query("insert into test values (default, 'two', 2)");

    let one   = this.db.query("select number from test where string = {one}", { one: "one" });
    let two   = this.db.query("select string from test where id = {0}", [2]);

    Assert.that(one.entry.length() == 1, "SELECT did not return exactly one entry");
    Assert.areEqual(one.entry.number, 1, "SELECT did not return 1");

    Assert.that(two.entry.length() == 1, "SELECT did not return exactly one entry");
    Assert.areEqual(two.entry.string, "two", "SELECT did not return 'two'");
  },

  testQueryBatch: function() {
    let res = this.db.query("insert into test (string, number) values ({0}, {1})",
			    { 0:"one", 1: 1}, ["two", 2],
			    <><e>three</e><e>3</e></>);
//    esxx.log.debug(res);
  },

  testTransaction: function() {
    let db = this.db;

    Assert.fnThrows(function() {
      db.query(function() {
	db.query("insert into test values (default, 'one', 1)");
	throw "Transaction rolled back";
      });
    }, "string", "Transaction did not throw a string");

    Assert.areEqual(db.query("select count(*) as cnt from test").entry.cnt, 0,
		    "Transaction #1 did not roll back");

    db.query(function() {
      db.query("insert into test values (default, 'one', 1)");
      db.query("insert into test values (default, 'two', 2)");
    });

    Assert.areEqual(db.query("select count(*) as cnt from test").entry.cnt, 2,
		    "Transaction #2 did not insert two row");
  },

  testMetaData: function() {
    this.db.query("insert into test values (default, 'one', 1)");

    let res = this.db.query("select * from test", { $meta: "meta" });

    Assert.areEqual([t.toString() for each (t in res.meta.*.@type)].join(','), 
		    "bigint,varchar,integer", "Query returned incorrect types");
  }

}));
