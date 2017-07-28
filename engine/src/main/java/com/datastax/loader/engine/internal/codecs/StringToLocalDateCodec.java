/*
 * Copyright (C) 2017 DataStax Inc.
 *
 * This software can be used solely with DataStax Enterprise. Please consult the license at
 * http://www.datastax.com/terms/datastax-dse-driver-license-terms
 */
package com.datastax.loader.engine.internal.codecs;

import com.datastax.driver.core.exceptions.InvalidTypeException;
import com.datastax.driver.extras.codecs.jdk8.LocalDateCodec;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

public class StringToLocalDateCodec extends StringToTemporalCodec<LocalDate> {

  public StringToLocalDateCodec(DateTimeFormatter parser) {
    super(LocalDateCodec.instance, parser);
  }

  @Override
  protected LocalDate convertFrom(String s) {
    TemporalAccessor temporal = parseAsTemporalAccessor(s);
    if (temporal == null) {
      return null;
    }
    try {
      return LocalDate.from(temporal);
    } catch (DateTimeException e) {
      throw new InvalidTypeException("Cannot parse local date:" + s, e);
    }
  }
}
