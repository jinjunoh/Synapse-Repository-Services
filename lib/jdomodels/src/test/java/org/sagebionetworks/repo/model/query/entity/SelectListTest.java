package org.sagebionetworks.repo.model.query.entity;

import static org.junit.Assert.*;

import java.util.LinkedList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.repo.model.query.jdo.NodeField;

import com.google.common.collect.Lists;

public class SelectListTest {
	
	List<String> nodeToEntitySql;
	IndexProvider indexProvider;
	
	@Before
	public void before(){
		indexProvider = new IndexProvider();
		nodeToEntitySql = new LinkedList<String>();
		for(NodeToEntity nte: NodeToEntity.values()){
			ColumnReference ref = new ColumnReference(nte.name(), indexProvider.nextIndex());
			SelectColumn sc = new SelectColumn(ref);
			nodeToEntitySql.add(sc.toSql());
		}
	}

	@Test
	public void testSelectStarEmpty(){
		SelectList list = new SelectList(new LinkedList<String>(), indexProvider);
		String sql = list.toSql();
		assertTrue(sql.startsWith("SELECT "));
		// should contain the SQL for each type
		for(String typeSql: nodeToEntitySql){
			assertTrue(sql.contains(typeSql));
		}
		assertTrue(list.isSelectStar());
	}
	
	@Test
	public void testSelectStarNull(){
		SelectList list = new SelectList(null, indexProvider);
		String sql = list.toSql();
		assertTrue(sql.startsWith("SELECT "));
		// should contain the SQL for each type
		for(String typeSql: nodeToEntitySql){
			assertTrue(sql.contains(typeSql));
		}
		assertTrue(list.isSelectStar());
	}
	
	@Test
	public void testSelectNodeFieldsOnly(){
		List<String> input = Lists.newArrayList(
				NodeField.ID.getFieldName()
				, NodeField.E_TAG.getFieldName()
				);
		SelectList list = new SelectList(input, indexProvider);
		assertEquals("SELECT E.ID AS 'id', E.ETAG AS 'eTag'", list.toSql());
		assertFalse(list.isSelectStar());
	}

	@Test
	public void testSelectAnnotationsOnly(){
		indexProvider = new IndexProvider();
		List<String> input = Lists.newArrayList(
				"foo"
				, "bar"
				);
		SelectList list = new SelectList(input, indexProvider);
		assertEquals("SELECT A0.ANNO_VALUE AS 'foo', A1.ANNO_VALUE AS 'bar'", list.toSql());
		assertFalse(list.isSelectStar());
	}
	
	@Test
	public void testSelectNodeFieldsAndAnnotations(){
		indexProvider = new IndexProvider();
		List<String> input = Lists.newArrayList(
				NodeField.ID.getFieldName()
				, "foo"
				);
		SelectList list = new SelectList(input, indexProvider);
		assertEquals("SELECT E.ID AS 'id', A1.ANNO_VALUE AS 'foo'", list.toSql());
		assertFalse(list.isSelectStar());
	}
}
