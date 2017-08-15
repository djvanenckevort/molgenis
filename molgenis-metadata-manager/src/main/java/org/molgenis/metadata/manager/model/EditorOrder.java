package org.molgenis.metadata.manager.model;

import com.google.auto.value.AutoValue;
import javax.annotation.Nullable;
import org.molgenis.gson.AutoGson;

@AutoValue
@AutoGson(autoValueClass = AutoValue_EditorOrder.class)
public abstract class EditorOrder {
  public abstract String getAttributeName();

  @Nullable
  public abstract String getDirection();

  public static EditorOrder create(String attributeName, @Nullable String direction) {
    return new AutoValue_EditorOrder(attributeName, direction);
  }
}
