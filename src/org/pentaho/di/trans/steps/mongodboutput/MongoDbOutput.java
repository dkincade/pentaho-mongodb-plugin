/*!
* Copyright 2010 - 2013 Pentaho Corporation.  All rights reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*/

package org.pentaho.di.trans.steps.mongodboutput;

import java.net.UnknownHostException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.pentaho.di.core.Const;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.mongo.AuthContext;
import org.pentaho.mongo.MongoUtils;

import com.mongodb.CommandResult;
import com.mongodb.DBObject;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteResult;

/**
 * Class providing an output step for writing data to a MongoDB collection.
 * Supports insert, truncate, upsert, multi-update (update all matching docs)
 * and modifier update (update only certain fields) operations. Can also create
 * and drop indexes based on one or more fields.
 * 
 * @author Mark Hall (mhall{[at]}pentaho{[dot]}com)
 */
public class MongoDbOutput extends BaseStep implements StepInterface {
  private static Class<?> PKG = MongoDbOutputMeta.class;

  protected MongoDbOutputMeta m_meta;
  protected MongoDbOutputData m_data;

  protected MongoDbOutputData.MongoTopLevel m_mongoTopLevelStructure = MongoDbOutputData.MongoTopLevel.INCONSISTENT;

  /** The batch size to use for insert operation */
  protected int m_batchInsertSize = 100;

  /** Holds a batch */
  protected List<DBObject> m_batch;

  protected int m_writeRetries = MongoDbOutputMeta.RETRIES;
  protected int m_writeRetryDelay = MongoDbOutputMeta.RETRY_DELAY;

  public MongoDbOutput(StepMeta stepMeta, StepDataInterface stepDataInterface,
      int copyNr, TransMeta transMeta, Trans trans) {
    super(stepMeta, stepDataInterface, copyNr, transMeta, trans);
  }

  @Override
  public boolean processRow(StepMetaInterface smi, StepDataInterface sdi)
      throws KettleException {
    AuthContext context = MongoUtils.createAuthContext(m_meta, this);
    try {
      return /* allow autoboxing */ context.doAs(new PrivilegedExceptionAction<Boolean>() {

        @Override
        public Boolean run() throws KettleException {

          Object[] row = getRow();
      
          if (row == null) {
            // no more output
      
            // check any remaining buffered objects
            if (m_batch != null && m_batch.size() > 0) {
              doBatch();
            }
      
            // INDEXING - http://www.mongodb.org/display/DOCS/Indexes
            // Indexing is computationally expensive - it needs to be
            // done after all data is inserted and done in the BACKGROUND.
      
            // UNIQUE indexes (prevent duplicates on the
            // keys in the index) and SPARSE indexes (don't index docs that
            // don't have the key field) - current limitation is that SPARSE
            // indexes can only have a single field
      
            List<MongoDbOutputMeta.MongoIndex> indexes = m_meta.getMongoIndexes();
            if (indexes != null && indexes.size() > 0) {
              logBasic(BaseMessages.getString(PKG,
                  "MongoDbOutput.Messages.ApplyingIndexOpps")); //$NON-NLS-1$
              m_data.applyIndexes(indexes, log, m_meta.getTruncate());
            }
      
            disconnect();
            setOutputDone();
            return false;
          }
      
          if (first) {
            first = false;
      
            m_batchInsertSize = 100;
      
            String batchInsert = environmentSubstitute(m_meta.getBatchInsertSize());
            if (!Const.isEmpty(batchInsert)) {
              m_batchInsertSize = Integer.parseInt(batchInsert);
            }
            m_batch = new ArrayList<DBObject>(m_batchInsertSize);
      
            // output the same as the input
            m_data.setOutputRowMeta(getInputRowMeta());
      
            m_mongoTopLevelStructure = MongoDbOutputData.checkTopLevelConsistency(
                m_meta.m_mongoFields, MongoDbOutput.this);
            // scan for top-level JSON document insert and validate
            // field specification in this case.
            m_data.m_hasTopLevelJSONDocInsert = MongoDbOutputData
                .scanForInsertTopLevelJSONDoc(m_meta.m_mongoFields);
      
            if (m_mongoTopLevelStructure == MongoDbOutputData.MongoTopLevel.INCONSISTENT) {
              throw new KettleException(BaseMessages.getString(PKG,
                  "MongoDbOutput.Messages.Error.InconsistentMongoTopLevel")); //$NON-NLS-1$
            }
      
            // first check our incoming fields against our meta data for fields to
            // insert
            // this fields is came to step input
            RowMetaInterface rmi = getInputRowMeta();
            // this fields we are going to use for mongo output
            List<MongoDbOutputMeta.MongoField> mongoFields = m_meta.getMongoFields();
            checkInputFieldsMatch( rmi, mongoFields );

            // copy and initialize mongo fields
            m_data.setMongoFields(m_meta.getMongoFields());
            m_data.init(MongoDbOutput.this);
      
            // check truncate
            if (m_meta.getTruncate()) {
              try {
                logBasic(BaseMessages.getString(PKG,
                    "MongoDbOutput.Messages.TruncatingCollection")); //$NON-NLS-1$
                m_data.getCollection().drop();
      
                // re-establish the collection
                String collection = environmentSubstitute(m_meta.getCollection());
                m_data.createCollection(collection);
                m_data.setCollection(m_data.getDB().getCollection(collection));
              } catch (Exception m) {
                disconnect();
                throw new KettleException(m.getMessage(), m);
              }
            }
          }
      
          if (!isStopped()) {
      
            if (m_meta.getUpsert()) {
              DBObject updateQuery = m_data.getQueryObject(m_data.m_userFields,
                  getInputRowMeta(), row, MongoDbOutput.this, m_mongoTopLevelStructure);
      
              if (log.isDebug()) {
                logDebug(BaseMessages.getString(PKG,
                    "MongoDbOutput.Messages.Debug.QueryForUpsert", updateQuery)); //$NON-NLS-1$
              }
      
              if (updateQuery != null) {
                // i.e. we have some non-null incoming query field values
                DBObject insertUpdate = null;
      
                // get the record to update the match with
                if (!m_meta.getModifierUpdate()) {
                  // complete record replace or insert
      
                  insertUpdate = MongoDbOutputData.kettleRowToMongo(
                      m_data.m_userFields, getInputRowMeta(), row, MongoDbOutput.this,
                      m_mongoTopLevelStructure, m_data.m_hasTopLevelJSONDocInsert);
                  if (log.isDebug()) {
                    logDebug(BaseMessages.getString(PKG,
                        "MongoDbOutput.Messages.Debug.InsertUpsertObject", //$NON-NLS-1$
                        insertUpdate));
                  }
      
                } else {
      
                  // specific field update or insert
                  insertUpdate = m_data.getModifierUpdateObject(m_data.m_userFields,
                      getInputRowMeta(), row, MongoDbOutput.this, m_mongoTopLevelStructure);
                  if (log.isDebug()) {
                    logDebug(BaseMessages.getString(PKG,
                        "MongoDbOutput.Messages.Debug.ModifierUpdateObject", //$NON-NLS-1$
                        insertUpdate));
                  }
                }
      
                if (insertUpdate != null) {
                  commitUpsert(updateQuery, insertUpdate);
                }
              }
            } else {
              // straight insert
      
              DBObject mongoInsert = MongoDbOutputData.kettleRowToMongo(
                  m_data.m_userFields, getInputRowMeta(), row, MongoDbOutput.this,
                  m_mongoTopLevelStructure, m_data.m_hasTopLevelJSONDocInsert);
      
              if (mongoInsert != null) {
                m_batch.add(mongoInsert);
              }
              if (m_batch.size() == m_batchInsertSize) {
                logDetailed(BaseMessages.getString(PKG,
                    "MongoDbOutput.Messages.CommitingABatch")); //$NON-NLS-1$
                doBatch();
              }
            }
          }
      
          return true;
        }
      });
    } catch (PrivilegedActionException e) {
      Throwable cause = e.getException();
      if (cause instanceof KettleException) {
        throw (KettleException) cause;
      } else {
        throw new KettleException("Unexpected error", e.getException());
      }
    }
  }

  protected void commitUpsert(DBObject updateQuery, DBObject insertUpdate)
      throws KettleException {

    int retrys = 0;
    MongoException lastEx = null;

    while (retrys <= m_writeRetries && !isStopped()) {
      WriteResult result = null;
      CommandResult cmd = null;
      try {
        // TODO It seems that doing an update() via a secondary node does not
        // generate any sort of exception or error result! (at least via
        // driver version 2.11.1). Transformation completes successfully
        // but no updates are made to the collection.
        // This is unlike doing an insert(), which generates
        // a MongoException if you are not talking to the primary. So we need
        // some logic to check whether or not the connection configuration
        // contains the primary in the replica set and give feedback if it
        // doesnt
        result = m_data.getCollection().update(updateQuery, insertUpdate, true,
            m_meta.getMulti());

        cmd = result.getLastError();
        if (cmd != null && !cmd.ok()) {
          String message = cmd.getErrorMessage();
          logError(BaseMessages.getString(PKG,
              "MongoDbOutput.Messages.Error.MongoReported", message)); //$NON-NLS-1$

          cmd.throwOnError();
        }
      } catch (MongoException me) {
        lastEx = me;
        retrys++;
        if (retrys <= m_writeRetries) {
          logError(BaseMessages.getString(PKG,
              "MongoDbOutput.Messages.Error.ErrorWritingToMongo", //$NON-NLS-1$
              me.toString()));
          logBasic(BaseMessages.getString(PKG,
              "MongoDbOutput.Messages.Message.Retry", m_writeRetryDelay)); //$NON-NLS-1$
          try {
            Thread.sleep(m_writeRetryDelay * 1000);
          } catch (InterruptedException e) {
          }
        }
      }

      if (cmd != null && cmd.ok()) {
        break;
      }
    }

    if ((retrys > m_writeRetries || isStopped()) && lastEx != null) {
      throw new KettleException(lastEx);
    }
  }

  protected void doBatch() throws KettleException {
    int retrys = 0;
    MongoException lastEx = null;

    while (retrys <= m_writeRetries && !isStopped()) {
      WriteResult result = null;
      CommandResult cmd = null;
      try {
        result = m_data.getCollection().insert(m_batch);
        cmd = result.getLastError();

        if (cmd != null && !cmd.ok()) {
          String message = cmd.getErrorMessage();
          logError(BaseMessages.getString(PKG,
              "MongoDbOutput.Messages.Error.MongoReported", message)); //$NON-NLS-1$

          cmd.throwOnError();
        }
      } catch (MongoException me) {
        lastEx = me;
        retrys++;
        if (retrys <= m_writeRetries) {
          logError(BaseMessages.getString(PKG,
              "MongoDbOutput.Messages.Error.ErrorWritingToMongo", //$NON-NLS-1$
              me.toString()));
          logBasic(BaseMessages.getString(PKG,
              "MongoDbOutput.Messages.Message.Retry", m_writeRetryDelay)); //$NON-NLS-1$
          try {
            Thread.sleep(m_writeRetryDelay * 1000);
          } catch (InterruptedException e) {
          }
        }
        // throw new KettleException(me.getMessage(), me);
      }

      if (cmd != null) {
        ServerAddress s = cmd.getServerUsed();
        if (s != null) {
          logDetailed(BaseMessages.getString(PKG,
              "MongoDbOutput.Messages.WroteBatchToServer", s.toString())); //$NON-NLS-1$
        }
      }

      if (cmd != null && cmd.ok()) {
        break;
      }
    }

    if ((retrys > m_writeRetries || isStopped()) && lastEx != null) {
      throw new KettleException(lastEx);
    }

    m_batch.clear();
  }

  @Override
  public boolean init(StepMetaInterface stepMetaInterface,
      StepDataInterface stepDataInterface) {
    if (super.init(stepMetaInterface, stepDataInterface)) {
      m_meta = (MongoDbOutputMeta) stepMetaInterface;
      m_data = (MongoDbOutputData) stepDataInterface;

      if (!Const.isEmpty(m_meta.getWriteRetries())) {
        try {
          m_writeRetries = Integer.parseInt(m_meta.getWriteRetries());
        } catch (NumberFormatException ex) {
        }
      }

      if (!Const.isEmpty(m_meta.getWriteRetryDelay())) {
        try {
          m_writeRetryDelay = Integer.parseInt(m_meta.getWriteRetryDelay());
        } catch (NumberFormatException ex) {
        }
      }

      String hostname = environmentSubstitute(m_meta.getHostnames());
      int port = Const.toInt(environmentSubstitute(m_meta.getPort()), 27017);
      String db = environmentSubstitute(m_meta.getDBName());
      String collection = environmentSubstitute(m_meta.getCollection());

      try {

        if (Const.isEmpty(db)) {
          throw new Exception(BaseMessages.getString(PKG,
              "MongoDbOutput.Messages.Error.NoDBSpecified")); //$NON-NLS-1$
        }

        if (Const.isEmpty(collection)) {
          throw new Exception(BaseMessages.getString(PKG,
              "MongoDbOutput.Messages.Error.NoCollectionSpecified")); //$NON-NLS-1$
        }

        if (!Const.isEmpty(m_meta.getUsername())) {
          String authInfo = (m_meta.getUseKerberosAuthentication() ? BaseMessages
              .getString(PKG, "MongoDbOutput.Message.KerberosAuthentication",
                  environmentSubstitute(m_meta.getUsername())) : BaseMessages
              .getString(PKG, "MongoDbOutput.Message.NormalAuthentication",
                  environmentSubstitute(m_meta.getUsername())));

          logBasic(authInfo);
        }

        m_data.setConnection(MongoDbOutputData.connect(m_meta, this, log));
        m_data.setDB(m_data.getConnection().getDB(db));

        if (Const.isEmpty(collection)) {
          throw new KettleException(BaseMessages.getString(PKG,
              "MongoDbOutput.Messages.Error.NoCollectionSpecified")); //$NON-NLS-1$
        }
        m_data.createCollection(collection);
        m_data.setCollection(m_data.getDB().getCollection(collection));

        return true;
      } catch (UnknownHostException ex) {
        logError(BaseMessages.getString(PKG,
            "MongoDbOutput.Messages.Error.UnknownHost", hostname), ex); //$NON-NLS-1$
        return false;
      } catch (Exception e) {
        logError(BaseMessages.getString(PKG,
            "MongoDbOutput.Messages.Error.ProblemConnecting", hostname, "" //$NON-NLS-1$ //$NON-NLS-2$
                + port), e);
        return false;
      }
    }

    return false;
  }

  protected void disconnect() {
    if (m_data != null) {
      MongoDbOutputData.disconnect(m_data.getConnection());
    }
  }

  @Override
  public void dispose(StepMetaInterface smi, StepDataInterface sdi) {
    if (m_data != null) {
      MongoDbOutputData.disconnect(m_data.getConnection());
    }

    super.dispose(smi, sdi);
  }
  
  final void checkInputFieldsMatch( RowMetaInterface rmi, List<MongoDbOutputMeta.MongoField> mongoFields ) throws KettleException{
      Set<String> expected = new HashSet<String>( mongoFields.size(), 1 );
      Set<String> actual = new HashSet<String>( rmi.getFieldNames().length, 1 );
      for (MongoDbOutputMeta.MongoField field : mongoFields) {
          String mongoMatch = environmentSubstitute(field.m_incomingFieldName);
          expected.add( mongoMatch );
      }
      for (int i = 0; i < rmi.size(); i++) {
      	String metaFieldName = rmi.getValueMeta(i).getName();
      	actual.add( metaFieldName );
      }

      //check that all expected fields is available in step input meta
      if ( !actual.containsAll( expected ) ){
      	//in this case some fields willn't be found in input step meta
      	expected.removeAll( actual );
      	StringBuffer b = new StringBuffer();
      	for ( String name : expected ){
      		b.append("'").append( name ).append("', ");
      	}
      	throw new KettleException(BaseMessages.getString(PKG, 
      			"MongoDbOutput.Messages.MongoField.Error.FieldsNotFoundInMetadata",
      			b.toString() ));
      }
      
      boolean found = actual.removeAll( expected );
      if ( !found ){
      	throw new KettleException(BaseMessages.getString(PKG,
                  "MongoDbOutput.Messages.Error.NotInsertingAnyFields")); //$NON-NLS-1$
      }
      
      if ( !actual.isEmpty() ){
      	//we have some fields that will not be inserted.
      	StringBuffer b = new StringBuffer();
      	for ( String name : actual ){
      		b.append("'").append( name ).append("', ");
      	}
      	//just put a log record on it
      	logBasic(BaseMessages.getString(PKG,
                  "MongoDbOutput.Messages.FieldsNotToBeInserted", b.toString() ) );
      }
  }  
}
