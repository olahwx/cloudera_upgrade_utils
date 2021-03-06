package com.streever.hive.perf;

import com.streever.hive.reporting.ReportingConf;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.hadoop.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.*;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import static java.sql.Types.*;

public class JDBCRecordIterator implements Runnable {

    private String jdbcUrl;
    private String username;
    private String password;
    private String query;
    private String pingHost;
    private Integer batchSize = 5000;
    private Integer delayWarning = 1000;
    private Boolean lite = Boolean.FALSE;
    private StringBuilder connectionDetails = new StringBuilder();
    private AtomicLong count = new AtomicLong(0);
    private AtomicLong size = new AtomicLong(0);
    private Date start;

    private Deque<Delay> delays = new ConcurrentLinkedDeque<Delay>();

    public AtomicLong getCount() {
        return count;
    }

    public AtomicLong getSize() {
        return size;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPingHost() {
        return pingHost;
    }

    public void setPingHost(String pingHost) {
        this.pingHost = pingHost;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Integer getDelayWarning() {
        return delayWarning;
    }

    public void setDelayWarning(Integer delayWarning) {
        this.delayWarning = delayWarning;
    }

    public Boolean getLite() {
        return lite;
    }

    public void setLite(Boolean lite) {
        this.lite = lite;
    }

    public StringBuilder getConnectionDetails() {
        return connectionDetails;
    }

    public Date getStart() {
        return start;
    }

    public Statistic getStat() {
        Statistic stat = Statistic.build(count.get(), size.get());
        return stat;
    }

    public void pushDelay(Delay delay) {
        delays.add(delay);
        if (delays.size() > 10)
            delays.removeFirst();
    }

    public String getDelays() {
        StringBuilder sb = new StringBuilder();
        if (delays.size() > 0) {
            sb.append(ReportingConf.ANSI_GREEN + "-----------------------------------" + ReportingConf.ANSI_RED).append("\n");
            for (Iterator iter = delays.iterator(); iter.hasNext(); ) {
                Delay delay = (Delay) iter.next();
                sb.append(delay).append("\n");
            }
            sb.append(ReportingConf.ANSI_GREEN + "-----------------------------------" + ReportingConf.ANSI_RESET).append("\n");
        }
        return sb.toString();
    }

    public String ping(String host)
            throws IOException {
        String s = null;

        List<String> commands = new ArrayList<String>();
        commands.add("ping");
        commands.add("-c");
        commands.add("5");
        commands.add(host);

        ProcessBuilder pb = new ProcessBuilder(commands);
        Process process = pb.start();

        BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
        BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));

        StringBuilder sb = new StringBuilder();
        sb.append(commands.toString()).append("\n");
        // read the output from the command
//        System.out.println("Here is the standard output of the command:\n");
        while ((s = stdInput.readLine()) != null) {
            sb.append(s).append("\n");
//            System.out.println(s);
        }

        // read any errors from the attempted command
//        System.out.println("Here is the standard error of the command (if any):\n");
        while ((s = stdError.readLine()) != null) {
            sb.append(s).append("\n");
//            System.out.println(s);
        }

        return sb.toString();
    }

    @Override
    public void run() {
        start = new Date();
        StopWatch stopWatch = new StopWatch();
        Connection conn = null;
        if (getPingHost() != null) {
            try {
                connectionDetails.append(ping(getPingHost())).append("\n");
            } catch (IOException e) {
                connectionDetails.append("Host doesn't support ping");
                e.printStackTrace();
            }
        }
        try {
            long splitTime = 0;
            stopWatch.start();
            stopWatch.split();
            connectionDetails.append("Connect Attempt  : " + stopWatch.getSplitTime()).append("ms\n");
            conn = DriverManager.getConnection(this.jdbcUrl, this.username, this.password);
            stopWatch.split();
            connectionDetails.append("Connected        : " + stopWatch.getSplitTime()).append("ms\n");
            Statement stmt = conn.createStatement();
            stopWatch.split();
            connectionDetails.append("Create Statement : " + stopWatch.getSplitTime()).append("ms\n");
            stmt.setFetchSize(this.batchSize);
            stopWatch.split();
            connectionDetails.append("Before Query     : " + stopWatch.getSplitTime()).append("ms\n");
            ResultSet rs = stmt.executeQuery(this.query);
            stopWatch.split();
            connectionDetails.append("Query Return     : " + stopWatch.getSplitTime()).append("ms\n");
            int[] columnTypes = null;
            if (!lite) {
                columnTypes = new int[rs.getMetaData().getColumnCount()];
                for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                    columnTypes[i] = rs.getMetaData().getColumnType(i + 1);
                }
            }
            stopWatch.split();
            connectionDetails.append("Start Iterating Results   : " + stopWatch.getSplitTime()).append("ms\n");
            long marker = System.currentTimeMillis();
            while (rs.next()) {
                if (System.currentTimeMillis() - marker > delayWarning) {
                    Delay delay = new Delay(System.currentTimeMillis() - marker, count.get());//                    delays.addLast(delay);
                    pushDelay(delay);
                }
                // reset marker
                marker = System.currentTimeMillis();
                long currentCount = count.getAndIncrement();
                if (!lite) {
                    for (int i = 0; i < columnTypes.length; i++) {
                        switch (columnTypes[i]) {
                            case BIT:
                                size.getAndAdd(1);//rs.getByte(i+1));
                                break;
                            case TINYINT:
                                size.getAndAdd(1);
                                break;
                            case SMALLINT:
                                size.getAndAdd(2);
                                break;
                            case INTEGER:
                                size.getAndAdd(4);
                                break;
                            case BIGINT:
                                size.getAndAdd(8);
                                break;
                            case FLOAT:
                                size.getAndAdd(4);
                                break;
                            case REAL:
                            case DOUBLE:
                                size.getAndAdd(8);
                                break;
                            case NUMERIC:
                                size.getAndAdd(8);
                                break;
                            case DECIMAL:
                                size.getAndAdd(8);
                                break;
                            case CHAR:
                            case VARCHAR:
                            case LONGVARCHAR:
                                String check = rs.getString(i + 1);
                                if (check != null)
                                    size.getAndAdd(check.length());
                                break;
                            case DATE:
                            case TIME:
                            case TIMESTAMP:
                                size.getAndAdd(8);
                                break;
                        }
                    }
                }
            }
            stopWatch.split();
            connectionDetails.append("Completed Iterating Results: " + stopWatch.getSplitTime()).append("ms\n");
            stmt.close();
            stopWatch.split();
            connectionDetails.append("Statement Closed           : " + stopWatch.getSplitTime()).append("ms\n");
            rs.close();
            stopWatch.split();
            connectionDetails.append("Resultset Closed           : " + stopWatch.getSplitTime()).append("ms\n");
        } catch (SQLException se) {
            stopWatch.split();
            connectionDetails.append("SQL Issue                  : " + stopWatch.getSplitTime()).append("ms\n");
            connectionDetails.append("** Message **\n").append(se.getMessage()).append("\n");
            se.printStackTrace();
        } catch (RuntimeException rt) {
            stopWatch.split();
            connectionDetails.append("Processing Issue           : " + stopWatch.getSplitTime()).append("ms\n");
            connectionDetails.append("   Message:\n").append(rt.getMessage()).append("\n");
            rt.printStackTrace();
        } catch (Throwable t) {
            stopWatch.split();
            connectionDetails.append("Processing Issue           : " + stopWatch.getSplitTime()).append("ms\n");
            connectionDetails.append("   Message:\n").append(t.getMessage()).append("\n");
            connectionDetails.append("   Kerberos Attempted connections without the proper Hadoop Libs will cause this.\n");
            t.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            stopWatch.split();
            connectionDetails.append("Process Completed          : " + stopWatch.getSplitTime()).append("ms\n");
            stopWatch.stop();

        }
    }

}