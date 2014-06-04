package org.sonar.server.search.es;

import com.google.common.collect.ImmutableSet;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.script.AbstractExecutableScript;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.NativeScriptFactory;

import java.util.Collection;
import java.util.Map;

/**
 * @since 4.4
 */
public class ListUpdate extends AbstractExecutableScript {

  public static final String NAME = "listUpdate";

  public static final String ID = "id";
  public static final String FIELD = "field";
  public static final String VALUE = "value";

  public static class UpdateListScriptFactory implements NativeScriptFactory {
    @Override
    public ExecutableScript newScript(@Nullable Map<String, Object> params) {
      String id = XContentMapValues.nodeStringValue(params.get(ID), null);
      String field = XContentMapValues.nodeStringValue(params.get(FIELD), null);
      Map value = XContentMapValues.nodeMapValue(params.get(VALUE), "Update item");

      if (id == null) {
        throw new IllegalStateException("Missing '" + ID + "' parameter");
      }
      if (field == null) {
        throw new IllegalStateException("Missing '" + FIELD + "' parameter");
      }
      if (value == null) {
        throw new IllegalStateException("Missing '" + VALUE + "' parameter");
      }

      return new ListUpdate(id, field, value);
    }
  }


  private final String id;
  private final String field;
  private final Map<String, Object> value;

  private Map<String, Object> ctx;

  public ListUpdate(String id, String field, Map<String, Object> value) {
    this.id = id;
    this.field = field;
    this.value = value;
  }

  @Override
  public void setNextVar(String name, Object value) {
    if (name.equals("ctx")) {
      ctx = (Map<String, Object>) value;
    }
  }

  @Override
  public Object unwrap(Object value) {
    return value;
  }

  @Override
  public Object run() {

    //Get the Document's source from ctx
    Map<String, Object> source = XContentMapValues.nodeMapValue(ctx.get("_source"), "source from context");

    //Get the Object for list update
    Object fieldValue = source.get(field);

    if (fieldValue == null) {
      // 0. The field does not exist (this is a upsert then)
      source.put(field, value);
    } else if (!XContentMapValues.isArray(fieldValue)) {
      // 1. The field is not yet a list
      source.put(field, ImmutableSet.of(fieldValue, value));
    } else {
      // 3. field is a list
      Collection items = ((Collection) fieldValue);
      for (Object item : items) {
        String idValue = XContentMapValues.nodeStringValue(item, null);
        if (idValue != null && idValue.equals(id)) {
          items.remove(item);
        }
      }
      items.add(value);
      source.put(field, items);
    }
    return null;
  }
}