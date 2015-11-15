package se.rosenbaum.bitcoiniblt.corpus;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.junit.Before;
import org.junit.Test;
import se.rosenbaum.bitcoiniblt.BlockStatsClientCoderTest;
import se.rosenbaum.bitcoiniblt.printer.CellCountVSFailureProbabilityPrinter;
import se.rosenbaum.bitcoiniblt.printer.FailureProbabilityPrinter;
import se.rosenbaum.bitcoiniblt.printer.IBLTSizeBlockStatsPrinter;
import se.rosenbaum.bitcoiniblt.printer.IBLTSizeVsFailureProbabilityPrinter;
import se.rosenbaum.bitcoiniblt.printer.ValueSizeCellCountPrinter;
import se.rosenbaum.bitcoiniblt.util.AggregateResultStats;
import se.rosenbaum.bitcoiniblt.util.BlockStatsResult;
import se.rosenbaum.bitcoiniblt.util.Interval;
import se.rosenbaum.bitcoiniblt.util.TestConfig;
import se.rosenbaum.bitcoiniblt.util.TransactionSets;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

public class CorpusDataTestManual extends BlockStatsClientCoderTest implements TransactionStore
{

	private CorpusData corpusStats;
	private File testFileDir;
	private File testResultDir;
	private File fullCorpusWithHints;

	@Before
	public void setup()
	{
		super.setup();

		String corpusHomePath = testProps.getProperty("corpus.directory");
		fullCorpusWithHints = new File(testProps.getProperty("rustyiblt.corpus.with.hints"));

		this.corpusStats = new CorpusData(new File(corpusHomePath));
		MAINNET_BLOCK = CorpusData.HIGHEST_BLOCK_HASH;
		try
		{
			corpusStats.calculateStatistics();
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
		blockCount = corpusStats.blockCount;

		testFileDir = new File(tempDirectory, "corpustestfiles");
		testFileDir.mkdirs();
		testResultDir = new File(tempDirectory, "corpustestresults");
		testResultDir.mkdirs();
	}

	@Test
	public void testFactor1() throws IOException
	{
		int factor = 1;
		int sampleCount = 1000;

		int extras = (int) Math.ceil(corpusStats.averageExtrasPerBlock) * factor;

		FailureProbabilityPrinter printer = new IBLTSizeVsFailureProbabilityPrinter(tempDirectory);

		CorpusDataTestConfig testConfig = new CorpusDataTestConfig(extras, extras, 100002);

		Interval interval = new Interval(0, testConfig.getCellCount());
		while (true)
		{
			AggregateResultStats result = testFailureProbability(printer, testConfig, sampleCount);

			if (result.getFailureProbability() > 0.02 && result.getFailureProbability() < 0.1)
			{
				printer.addResult(testConfig, result);
			}

			if (result.getFailureProbability() < 0.05)
			{
				interval.setHigh(testConfig.getCellCount());
			}
			else
			{
				interval.setLow(testConfig.getCellCount());
			}
			testConfig = new CorpusDataTestConfig(extras, extras, interval.nextValue(testConfig));

			if (!interval.isInsideInterval(testConfig.getCellCount()))
			{
				break;
			}
		}

		printer.finish();
	}

	@Test
	public void testFromTestFiles() throws Exception
	{
		int cellCount = 300;

		TestConfigGenerator configGenerator = null;
		for (int factor : new int[] { 1, 10, 100, 1000 })
		{
			FailureProbabilityPrinter printer = new IBLTSizeVsFailureProbabilityPrinter(tempDirectory);
			if (factor > 1)
			{
				cellCount = configGenerator.getCellCount() * 9;
			}
			configGenerator = new TestFileTestConfigGenerator(getFile(factor), 3, 8, 64, 4, cellCount);

			configGenerator = calculateSizeFromTargetProbability(printer, getFile(factor), configGenerator, factor, 0.05);
		}
	}

	@Test
	public void testFromTestFile() throws Exception
	{
		int cellCount = 6000;
		int factor = 100;
		TestFileTestConfigGenerator configGenerator;

		FailureProbabilityPrinter printer = new IBLTSizeVsFailureProbabilityPrinter(tempDirectory);
		configGenerator = new TestFileTestConfigGenerator(getFile(factor), 3, 8, 64, 4, cellCount);

		calculateSizeFromTargetProbability(printer, getFile(factor), configGenerator, factor, 0.05);
	}

	@Test
	public void testValueSizeFor5PercentFailureProbabilityFromRealDataFileMultipleTimes() throws Exception
	{
		for (int i = 0; i < 3; i++)
		{
			testValueSizeFor5PercentFailureProbabilityFromRealDataFile();
		}
	}

	@Test
	public void testValueSizeFor5PercentFailureProbabilityFromRealDataFile() throws Exception
	{
		int cellCount = 32385;
		File testFile = new File(testFileDir, "test-real.txt");
		TestConfigGenerator configGenerator = new TestFileTestConfigGenerator(testFile, 3, 8, 64, 4, cellCount);

		int[] category = new int[] { 8, 16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 256, 270, 280, 512 };
		IBLTSizeBlockStatsPrinter valueSizeCellCountPrinter = new ValueSizeCellCountPrinter(tempDirectory, category.length,
				"IBLT size for 5% failure probability, corpus-au");

		for (int i = 0; i < category.length; i++)
		{
			FailureProbabilityPrinter failureProbabilityPrinter = new CellCountVSFailureProbabilityPrinter(tempDirectory);
			configGenerator.setValueSize(category[i]);

			Interval interval = new Interval(0, configGenerator.getCellCount());
			AggregateResultStats closestResult = null;
			TestConfigGenerator closestTestConfig = null;
			while (true)
			{
				AggregateResultStats result = testFailureProbabilityForConfigGenerator(failureProbabilityPrinter, configGenerator);
				failureProbabilityPrinter.addResult(configGenerator, result);
				if (result.getFailureProbability() <= 0.05)
				{
					if (result.getFailureProbability() == 0.05)
					{
						interval.setLow(configGenerator.getCellCount());
					}
					interval.setHigh(configGenerator.getCellCount());
					closestResult = result;
					closestTestConfig = configGenerator;
				}
				else
				{
					interval.setLow(configGenerator.getCellCount());
				}
				configGenerator = new TestFileTestConfigGenerator(testFile, configGenerator.getHashFunctionCount(), configGenerator.getKeySize(),
						configGenerator.getValueSize(), configGenerator.getKeyHashSize(), interval.nextValue(configGenerator));

				if (!interval.isInsideInterval(configGenerator.getCellCount()))
				{
					configGenerator.setCellCount(interval.getHigh() * 2);
					break;
				}
			}
			failureProbabilityPrinter.finish();
			valueSizeCellCountPrinter.addResult(closestTestConfig, closestResult);
		}
		valueSizeCellCountPrinter.finish();
	}

	@Test
	public void testFromRealDataFile() throws Exception
	{
		int cellCount = 4200;
		File testFile = new File(testFileDir, "test-real.txt");

		TestFileTestConfigGenerator configGenerator = null;
		for (double targetProbability : new double[] { 0.04, 0.05, 0.06 })
		{
			FailureProbabilityPrinter printer = new IBLTSizeVsFailureProbabilityPrinter(tempDirectory);
			configGenerator = new TestFileTestConfigGenerator(testFile, 3, 8, 64, 4, configGenerator == null ? cellCount
					: configGenerator.getCellCount());

			calculateSizeFromTargetProbability(printer, testFile, configGenerator, -1, targetProbability);
		}
	}

	private TestConfigGenerator calculateSizeFromTargetProbability(FailureProbabilityPrinter printer, File testFile,
			TestConfigGenerator configGenerator, int factor, double targetProbability) throws Exception
	{
		Interval interval = new Interval(0, configGenerator.getCellCount());
		AggregateResultStats closestResult = null;
		TestConfigGenerator closestTestConfig = null;
		while (true)
		{
			AggregateResultStats result = testFailureProbabilityForConfigGenerator(printer, configGenerator);
			printer.addResult(configGenerator, result);
			if (result.getFailureProbability() <= targetProbability)
			{
				if (result.getFailureProbability() == targetProbability)
				{
					interval.setLow(configGenerator.getCellCount());
				}
				interval.setHigh(configGenerator.getCellCount());
				closestResult = result;
				closestTestConfig = configGenerator;
			}
			else
			{
				interval.setLow(configGenerator.getCellCount());
			}
			configGenerator = configGenerator.cloneGenerator();
			configGenerator.setCellCount(interval.nextValue(configGenerator));

			if (!interval.isInsideInterval(configGenerator.getCellCount()))
			{
				configGenerator.setCellCount(interval.getHigh());
				break;
			}
		}
		if (factor == -1)
		{
			printTestResultFile(closestTestConfig, closestResult, testFile, targetProbability);
		}
		else
		{
			printTestResultFile(closestTestConfig, closestResult, factor, testFile, targetProbability);
		}
		printer.finish();
		return closestTestConfig;
	}

	@Test
	public void testGenerateTestFileFactor1_10_100_1000() throws IOException
	{
		int sampleCount = 1000;

		for (int i = 0; i < 4; i++)
		{
			createTestFile(sampleCount, i);
		}
	}

	@Test
	public void testFindPercentilesOfExtras() throws IOException
	{
		int averageExtras = (int) Math.ceil(corpusStats.averageExtrasPerBlock);
		int countBelowEqualAverageExtras = 0;
		int countMoreThanAverageExtras = 0;
		List<Integer> unknowns = new ArrayList<Integer>();
		for (CorpusData.Node node : CorpusData.Node.values())
		{
			AverageExtrasPercentile handler = new AverageExtrasPercentile();
			corpusStats.getStats(node, handler);
			blockCount += handler.blocks.size();

			for (IntPair intPair : handler.blocks.values())
			{
				unknowns.add(intPair.unknowns);
				if (intPair.unknowns <= averageExtras)
				{
					countBelowEqualAverageExtras++;
				}
				else
				{
					countMoreThanAverageExtras++;
				}
			}
		}

		// processBlocks(CorpusData.HIGHEST_BLOCK_HASH, 720, )
		/*
		 * 1. Collect all extras (not coinbases) for all blocks and nodes from corpus. Calculate the average extras, E,
		 * over the remaining. 2. Calculate the average tx rate, R, over the corpus. Sum the number of transactions in
		 * all blocks and divide it with the data collection period in seconds. 3. Now calculate the
		 * "extras per tx rate", E/R. 4. Absents, A, is calculated from E and the ratio extras/absent 5. Assume that E/R
		 * is constant and that the extras/absent ratio holds for all tx rates.
		 */

		System.out.println("Assumed extras/absents: 1/1");

		System.out.println("Number of AU blocks: " + corpusStats.blockCount);
		System.out.println("Lowest/highes block: " + corpusStats.lowestBlock + "/" + corpusStats.highestBlock);
		System.out.println("Exact avg extras   : " + corpusStats.averageExtrasPerBlock);
		System.out.println("Ceil of extras, E  : " + averageExtras);
		System.out.println("Avg tx rate, R     : " + corpusStats.txRate);
		System.out.println("Avg E/R            : " + corpusStats.extrasPerTxRate);

		System.out.println("Count <= E         : " + countBelowEqualAverageExtras);
		System.out.println("Count >  E         : " + countMoreThanAverageExtras);
		System.out
				.println("Percentage below   : " + 100 * countBelowEqualAverageExtras / (countBelowEqualAverageExtras + countMoreThanAverageExtras));

		Collections.sort(unknowns);
		System.out.println("Rough percentiles:");
		int size = unknowns.size();
		for (int i = 1; i <= 9; i++)
		{
			int percent = 10 * i;
			System.out.println(percent + "% has <= " + unknowns.get(size * i / 10 - 1) + " extras");
		}
		for (int i = 1; i <= 10; i++)
		{
			int percent = 90 + i;
			System.out.println(percent + "% has <= " + unknowns.get(size * (90 + i) / 100 - 1) + " extras");
		}
	}

	@Test
	public void createTestFileMimicCorpus() throws IOException
	{
		FileWriter fileWriter = new FileWriter(new File(testFileDir, "test-real.txt"));
		final TestFilePrinter testFilePrinter = new TestFilePrinter(fileWriter);

		final List<String> extra = new ArrayList<String>();
		final List<String> absent = new ArrayList<String>();

		corpusStats.getStats(CorpusData.Node.AU, new CorpusData.RecordHandler()
		{
			boolean firstBlock = true;

			public void handle(Record record)
			{
				if (record.type == Type.COINBASE)
				{
					if (!firstBlock)
					{
						while (absent.size() < extra.size())
						{
							List<Transaction> randomTransactions = getRandomTransactions(extra.size() - absent.size(), false);
							for (Transaction randomTransaction : randomTransactions)
							{
								String hashAsString = randomTransaction.getHashAsString();
								if (!absent.contains(hashAsString))
								{
									absent.add(hashAsString);
								}
							}
						}
						testFilePrinter.writeTransactions(extra, absent);
						extra.clear();
						absent.clear();
					}
					else
					{
						firstBlock = false;
					}
				}
				else if (record.type == Type.UNKNOWN)
				{
					extra.add(new Sha256Hash(record.txid).toString());
				}
				else if (record.type == Type.MEMPOOL_ONLY)
				{
					if (absent.size() < extra.size())
					{
						// Fill up with made up absent transactions, by taking equally many from MEMPOOL_ONLY
						// as there are extra.
						Sha256Hash sha256Hash = new Sha256Hash(record.txid);
						try
						{
							if (getTransaction(sha256Hash) != null)
							{
								absent.add(sha256Hash.toString());
							}
						}
						catch (IOException e)
						{
							throw new RuntimeException(e);
						}
					}
				}
			}
		});

		testFilePrinter.writeTransactions(extra, absent);
		fileWriter.close();
	}

	private int averageBlockSize()
	{
		int totalBlockSize = 0;
		Block block = getBlock(new Sha256Hash(MAINNET_BLOCK));
		for (int i = 1; i < corpusStats.blockCount; i++)
		{
			totalBlockSize += block.getOptimalEncodingMessageSize();
			block = getBlock(block.getPrevBlockHash());
		}
		return totalBlockSize / corpusStats.blockCount;
	}

	private void printTestResultFile(TestConfig testConfig, AggregateResultStats result, File inputFile, double targetProbability) throws IOException
	{
		File resultFile = getResultFile("test-result-real");
		PrintWriter out = new PrintWriter(new FileWriter(resultFile));
		out.println("Input file                    : " + inputFile.getName());
		out.println("Average block size [Bytes]    : " + averageBlockSize());
		out.println("Average tx count per block    : " + (corpusStats.txCount / corpusStats.blockCount));
		printCommon(testConfig, result, out, targetProbability);
		out.flush();
		out.close();
	}

	private void printTestResultFile(TestConfig testConfig, AggregateResultStats result, int factor, File inputFile, double targetProbability)
			throws IOException
	{
		File resultFile = getResultFile("test-result-factor-" + factor);
		PrintWriter out = new PrintWriter(new FileWriter(resultFile));
		out.println("Input file                    : " + inputFile.getName());
		out.println("Estimated block size [Bytes]  : " + averageBlockSize() * factor);
		out.println("Estimated tx count per block  : " + (corpusStats.txCount / corpusStats.blockCount) * factor);
		out.println("Extra tx                      : " + testConfig.getExtraTxCount());
		out.println("Absent tx                     : " + testConfig.getAbsentTxCount());
		printCommon(testConfig, result, out, targetProbability);
		out.flush();
		out.close();
	}

	private void printCommon(TestConfig testConfig, AggregateResultStats result, PrintWriter out, double targetProbability)
	{
		out.println("Sample count                  : " + (result.getFailures() + result.getSuccesses()));
		out.println("Target failure probability    : " + targetProbability);
		out.println("IBLT size                     : " + testConfig.getIbltSize());
		out.println("Cell count                    : " + testConfig.getCellCount());
		out.println("Hash functions                : " + testConfig.getHashFunctionCount());
		out.println("Key size                      : " + testConfig.getKeySize());
		out.println("Value size                    : " + testConfig.getValueSize());
		out.println("KeyHashSize                   : " + testConfig.getKeyHashSize());
	}

	private File getResultFile(String prefix)
	{
		DateFormat dateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
		return new File(testResultDir, prefix + "-" + dateFormat.format(new Date()) + ".txt");
	}

	protected AggregateResultStats testFailureProbabilityForConfigGenerator(FailureProbabilityPrinter printer, TestConfigGenerator configGenerator)
			throws Exception
	{
		AggregateResultStats stats = new AggregateResultStats();

		TestConfig config = configGenerator.createNextTestConfig();
		int i = 1;
		while (config != null)
		{
			BlockStatsResult result = testBlockStats(config);
			stats.addSample(result);
			if (i % 100 == 0)
			{
				printer.logResult(config, stats);
			}
			config = configGenerator.createNextTestConfig();
			i++;
		}
		return stats;
	}

	private static class TestFilePrinter
	{
		Writer writer;

		private TestFilePrinter(Writer writer)
		{
			this.writer = writer;
		}

		private void printTransactionSets(TransactionSets sets) throws IOException
		{
			writer.write("extra:");
			writeTransactions(sets.getSendersTransactions());
			writer.write("absent:");
			writeTransactions(sets.getReceiversTransactions());
		}

		private void writeTransactions(List<Transaction> transactions) throws IOException
		{
			boolean first = true;
			for (Transaction transaction : transactions)
			{
				if (!first)
				{
					writer.write(",");
				}
				first = false;
				writer.write(transaction.getHash().toString());
			}
			writer.write("\n");
		}

		private void writeTransactions(List<String> extra, List<String> absent)
		{
			try
			{
				writer.write("extra:");
				writeTransactionStrings(extra);
				writer.write("absent:");
				writeTransactionStrings(absent);
			}
			catch (IOException e)
			{
				throw new RuntimeException(e);
			}
		}

		private void writeTransactionStrings(List<String> transactions) throws IOException
		{
			boolean first = true;
			for (String transaction : transactions)
			{
				if (!first)
				{
					writer.write(",");
				}
				first = false;
				writer.write(transaction);
			}
			writer.write("\n");
		}
	}

	private static class IntPair
	{
		int unknowns = 0;
		int knowns = 0;
	}

	private static class AverageExtrasPercentile implements CorpusData.RecordHandler
	{
		Map<Integer, IntPair> blocks = new HashMap<Integer, IntPair>();
		IntPair currentBlock = new IntPair();
		int currentHeight = 0;

		public void handle(Record record)
		{
			if (record.type == Type.COINBASE)
			{
				blocks.put(currentHeight, currentBlock);
				currentBlock = new IntPair();
				currentHeight = record.blockNumber;
			}
			else if (record.type == Type.UNKNOWN)
			{
				currentBlock.unknowns++;
			}
			else if (record.type == Type.KNOWN)
			{
				currentBlock.knowns++;
			}
		}
	}

	private void createTestFile(long sampleCount, long factorExponent) throws IOException
	{
		int factor = (int) Math.pow(10, factorExponent);
		int extras = (int) Math.ceil(corpusStats.averageExtrasPerBlock) * factor;
		CorpusDataTestConfig testConfig = new CorpusDataTestConfig(extras, extras, 100002);
		File file = getFile(factor);

		FileWriter fileWriter = new FileWriter(file);
		TestFilePrinter testFilePrinter = new TestFilePrinter(fileWriter);

		for (int i = 0; i < sampleCount; i++)
		{
			testFilePrinter.printTransactionSets(testConfig.createTransactionSets());
		}

		fileWriter.close();
	}

	private File getFile(long factor)
	{
		return new File(testFileDir, "test-factor-" + factor + ".txt");
	}

	private abstract class TestConfigGenerator extends TestConfig
	{

		public TestConfigGenerator(int txCount, int extraTxCount, int absentTxCount, int hashFunctionCount, int keySize, int valueSize,
				int keyHashSize, int cellCount)
		{
			super(txCount, extraTxCount, absentTxCount, hashFunctionCount, keySize, valueSize, keyHashSize, cellCount);
		}

		public abstract TestConfig createNextTestConfig() throws Exception;

		public abstract TestConfigGenerator cloneGenerator() throws Exception;
	}

	private class TestFileTestConfigGenerator extends TestConfigGenerator
	{
		private final BufferedReader fileReader;
		private final File inputFile;

		public TestFileTestConfigGenerator(File file, int hashFunctionCount, int keySize, int valueSize, int keyHashSize, int cellCount)
				throws FileNotFoundException
		{
			super(0, 0, 0, hashFunctionCount, keySize, valueSize, keyHashSize, cellCount);
			this.inputFile = file;
			this.fileReader = new BufferedReader(new FileReader(file));
		}

		@Override
		public TestConfigGenerator cloneGenerator() throws Exception
		{
			return new TestFileTestConfigGenerator(inputFile, getHashFunctionCount(), getKeySize(), getValueSize(), getKeyHashSize(), getCellCount());
		}

		public TestConfig createNextTestConfig()
		{
			TransactionSets nextTransactionSets = createNextTransactionSets();
			if (nextTransactionSets == null)
			{
				return null;
			}
			setExtraTxCount(nextTransactionSets.getSendersTransactions().size());
			setAbsentTxCount(nextTransactionSets.getReceiversTransactions().size());
			return new TransactionSetsTestConfig(nextTransactionSets, getHashFunctionCount(), getKeySize(), getValueSize(), getKeyHashSize(),
					getCellCount());
		}

		private TransactionSets createNextTransactionSets()
		{
			try
			{
				String line = fileReader.readLine();
				if (line == null)
				{
					fileReader.close();
					return null;
				}
				TransactionSets transactionSets = new TransactionSets();
				transactionSets.setSendersTransactions(new ArrayList<Transaction>());
				transactionSets.setReceiversTransactions(new ArrayList<Transaction>());
				line = line.substring("extra:".length());
				addTransactionsToList(line, transactionSets.getSendersTransactions());

				line = fileReader.readLine();
				line = line.substring("absent:".length());
				addTransactionsToList(line, transactionSets.getReceiversTransactions());
				return transactionSets;
			}
			catch (IOException e)
			{
				throw new RuntimeException(e);
			}
		}

		private void addTransactionsToList(String line, List<Transaction> transactions) throws IOException
		{
			StringTokenizer tokenizer = new StringTokenizer(line, ",");
			while (tokenizer.hasMoreTokens())
			{
				String hashString = tokenizer.nextToken();
				Transaction transaction = getTransaction(new Sha256Hash(hashString));
				if (transaction == null)
				{
					throw new RuntimeException("Couldn't find transaction " + hashString);
				}
				transactions.add(transaction);
			}
		}

		@Override
		public TransactionSets createTransactionSets()
		{
			return null;
		}

	}

	private class TransactionSetsTestConfig extends TestConfig
	{
		TransactionSets transactionSets;

		public TransactionSetsTestConfig(TransactionSets sets, int hashFunctionCount, int keySize, int valueSize, int keyHashSize, int cellCount)
		{
			super(0, 0, 0, hashFunctionCount, keySize, valueSize, keyHashSize, cellCount);
			this.transactionSets = sets;
			setAbsentTxCount(sets.getReceiversTransactions().size());
			setExtraTxCount(sets.getSendersTransactions().size());
		}

		@Override
		public TransactionSets createTransactionSets()
		{
			return transactionSets;
		}
	}

	private class CorpusDataTestConfig extends TestConfig
	{

		public CorpusDataTestConfig(int extraTxCount, int absentTxCount, int cellCount)
		{
			super(0, extraTxCount, absentTxCount, 3, 8, 64, 4, cellCount);
		}

		@Override
		public TransactionSets createTransactionSets()
		{
			List<Transaction> randomTransactions = getRandomTransactions(getExtraTxCount() + getAbsentTxCount(), false);
			TransactionSets transactionSets = new TransactionSets();
			// As with most other tests, we just care about differences. Transactions that are both in sender's and
			// receiver's transacitons will just be added and deleted so they don't affect the result.
			transactionSets.setSendersTransactions(randomTransactions.subList(0, getExtraTxCount()));
			transactionSets.setReceiversTransactions(randomTransactions.subList(getExtraTxCount(), getExtraTxCount() + getAbsentTxCount()));
			return transactionSets;
		}
	}

	private class FullCorpusWithHintsTestConfigGenerator extends TestConfigGenerator
	{
		IBLTBlockStream blockStream;
		List<IBLTBlockStream.IBLTBlockTransfer> transfers;
		File inputFileFromRustysBitcoinIblt;

		private FullCorpusWithHintsTestConfigGenerator(File inputFileFromRustysBitcoinIblt, int cellCount) throws IOException
		{
			super(0, 0, 0, 3, 8, 64, 0, cellCount);
			this.inputFileFromRustysBitcoinIblt = inputFileFromRustysBitcoinIblt;
			blockStream = new IBLTBlockStream(this.inputFileFromRustysBitcoinIblt, CorpusDataTestManual.this);
		}

		public TestConfig createNextTestConfig() throws Exception
		{
			if (transfers == null || transfers.isEmpty())
			{
				transfers = blockStream.getNextBlockTransfers();
				if (transfers == null)
				{
					return null;
				}
			}
			IBLTBlockStream.IBLTBlockTransfer transfer = transfers.remove(0);

			List<Transaction> blockOnlyTransactions = new ArrayList<Transaction>();
			for (Sha256Hash blockOnly : transfer.getBlockOnly())
			{
				Transaction transaction = getTransaction(blockOnly);
				if (transaction == null)
				{
					throw new RuntimeException("Couldn't find transaction " + blockOnly);
				}
				blockOnlyTransactions.add(transaction);
			}
			List<Transaction> mempoolOnlyTransactions = new ArrayList<Transaction>();
			for (Sha256Hash mempoolOnly : transfer.getMempoolOnly())
			{
				Transaction transaction = getTransaction(mempoolOnly);
				if (transaction == null)
				{
					throw new RuntimeException("Couldn't find transaction " + mempoolOnly);
				}
				mempoolOnlyTransactions.add(transaction);
			}
			TransactionSets transactionSets = new TransactionSets();
			transactionSets.setReceiversTransactions(mempoolOnlyTransactions);
			transactionSets.setSendersTransactions(blockOnlyTransactions);
			TransactionSetsTestConfig testConfig = new TransactionSetsTestConfig(transactionSets, getHashFunctionCount(), getKeySize(), getValueSize(), getKeyHashSize(), getCellCount());
			return testConfig;
		}

		@Override
		public TestConfigGenerator cloneGenerator() throws Exception
		{
			return new FullCorpusWithHintsTestConfigGenerator(inputFileFromRustysBitcoinIblt, getCellCount());
		}

		@Override
		public TransactionSets createTransactionSets()
		{
			return null;
		}
	}

	@Test
	public void testFromFullCorpusTestWithHints() throws Exception
	{
		int cellCount = 249;
		FailureProbabilityPrinter printer = new IBLTSizeVsFailureProbabilityPrinter(tempDirectory);
		FullCorpusWithHintsTestConfigGenerator configGenerator = new FullCorpusWithHintsTestConfigGenerator(fullCorpusWithHints, cellCount);

		calculateSizeFromTargetProbability(printer, fullCorpusWithHints, configGenerator, -1, 0.05);
	}

	@Test
	public void testCalculateTotalOverhead() throws Exception
	{
		IBLTBlockStream blockStream = new IBLTBlockStream(fullCorpusWithHints, CorpusDataTestManual.this);
		List<IBLTBlockStream.IBLTBlockTransfer> blockTransfers = blockStream.getNextBlockTransfers();
		int totalOverhead = 0;
		print("height,from,to,block-only,mempool-only%n");
		while (blockTransfers != null)
		{
			for (IBLTBlockStream.IBLTBlockTransfer blockTransfer : blockTransfers)
			{
				IBLTBlockStream.IBLTBlock ibltBlock = blockTransfer.ibltBlock;
				print("%d,%s,%s,%d,%d%n", ibltBlock.getHeight(), ibltBlock.ibltData.getNodeName(), blockTransfer.receiverTxGuessData.getNodeName(),
						blockTransfer.getBlockOnly().size(), blockTransfer.getMempoolOnly().size());
				totalOverhead += blockTransfer.getOverhead();
			}
			blockTransfers = blockStream.getNextBlockTransfers();
		}
		System.out.println("Total overhead = " + totalOverhead);
	}

	private class SpecificSaltTestConfig extends TransactionSetsTestConfig {
		long salt = 0;
		SpecificSaltTestConfig(TransactionSets sets, int hashFunctionsCount, int keySize, int valueSize,
							   int keyHashSize, int cellCount) {
			super(sets, hashFunctionsCount, keySize, valueSize, keyHashSize, cellCount);
		}

		private void setSalt(long salt) {
			this.salt = salt;
		}

		@Override
		public byte[] getSalt() {
			byte[] saltBytes = new byte[32];
			ByteBuffer byteBuffer = ByteBuffer.wrap(saltBytes);
			byteBuffer.putLong(salt);
			return saltBytes;
		}
	}

	@Test
	public void testInfiniteLoop() throws IOException {
		String senderTx = "882e5a73ff3744c59dcbe1286aea95d6742b6264b492a5b62450c420f68a7f80";   // Stor 1110 bytes
		String receiverTx = "b31d8ab7b0f7afff6f780be0ddebf64b146986ab3c5f64322acf18e4970e183e"; // Liten 369 bytes
		int cellCount = 45;

		TransactionSets sets = new TransactionSets();
		sets.setSendersTransactions(Collections.singletonList(getTransaction(new Sha256Hash(senderTx))));
		sets.setReceiversTransactions(Collections.singletonList(getTransaction(new Sha256Hash(receiverTx))));

		SpecificSaltTestConfig testConfig = new SpecificSaltTestConfig(sets, 3, 8, 64, 0, cellCount);

		long salt = 22;

		testConfig.setSalt(salt);
		BlockStatsResult blockStatsResult = testBlockStats(testConfig);

		print("%d Success: %s%n", salt-1, blockStatsResult.isSuccess());
	}

	private void print(String message, Object... params)
	{
		System.out.printf(message, params);
	}
}
