package iie.mm.server;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

public class StorePhoto {
	private ServerConf conf;
	private String localHostName;
	private int serverport;							//本机监听的端口,在这里的作用就是构造存图片时返回值
	private String destRoot = "./mm_data/";
	private Set<String> storeArray = new HashSet<String>();
	private long blocksize;							//文件块的大小，单位是B
	
	private static Map<String, StoreSetContext> writeContextHash;
	private Map<String, RandomAccessFile> readRafHash;			//读文件时的随机访问流，用哈希来缓存
	private Jedis jedis;
	
	private TimeLimitedCacheMap lookupCache = new TimeLimitedCacheMap(10, 60, 300, TimeUnit.SECONDS);

	public class StoreSetContext {
		public String set;
		
		//当前可写的块
		private long curBlock = -1;
		private long offset = 0;
		//代表要写的块的文件
		private File newf = null;
		//写当前的块的随机访问流
		private RandomAccessFile raf = null;
		
		private String path = null;
		
		public StoreSetContext(String set) {
			// 根据set和md5构造存储的路径
			StringBuffer sb = new StringBuffer();
			sb.append(destRoot);
			sb.append(set);
			sb.append("/");
			path = sb.toString();
			//存储文件的文件夹的相对路径，不包含文件名
			File dir = new File(path);
			if (!dir.exists()) 
				dir.mkdirs();
			
			this.set = set;
		}
	}
	
	public StorePhoto(ServerConf conf) {
		this.conf = conf;
		serverport = conf.getServerPort();
		blocksize = conf.getBlockSize();
		storeArray = conf.getStoreArray();
		if (storeArray.size() == 0) {
			storeArray.add(".");
		}

		jedis = RedisFactory.getNewInstance(conf.getRedisHost(), conf.getRedisPort());

		localHostName = conf.getNodeName();
		readRafHash = new ConcurrentHashMap<String, RandomAccessFile>();
		writeContextHash = new ConcurrentHashMap<String, StoreSetContext>();
	}

	/**
	 * 把content代表的图片内容,存储起来,把小图片合并成一个块,块大小由配置文件中blocksize指定.
	 * 文件存储在destRoot下，然后按照set分第一层子目录
	 * @param set	集合名
	 * @param md5	文件的md5
	 * @param content	文件内容
	 * @return		type#set#node#port＃block＃offset＃length,这几个信息通过redis存储,分别表示元信息类型,该图片所属集合,所在节点,
	 * 				节点的端口号,所在相对路径（包括完整文件名）,位于所在块的偏移的字节数，该图片的字节数
	 */
	public String storePhoto(String set, String md5, byte[] content, int coff, int clen) {
		StringBuffer rVal = new StringBuffer();
		
		StoreSetContext ssc = writeContextHash.get(set);
		
		if (ssc == null) {
			ssc = new StoreSetContext(set);
			writeContextHash.put(set, ssc);
		}

		//找到当前可写的文件块,如果当前不够大,或不存在,则新创建一个,命名block＿id,id递增,redis中只存储id
		//用curBlock缓存当前可写的块，减少查询jedis的次数
		
		try {
			if (ssc.curBlock < 0) {
				String reply = jedis.get(set + ".blk." + localHostName);
				if (reply != null) {
					ssc.curBlock = Long.parseLong(reply);		//需要通过节点名字来标示不同节点上相同名字的集合
					ssc.newf = new File(ssc.path + "b" + ssc.curBlock);
				} else {
					ssc.curBlock = 0;
					ssc.newf = new File(ssc.path + "b" + ssc.curBlock);
					//把集合和它所在节点记录在redis的set里,方便删除,set.srvs表示set所在的服务器的位置
					jedis.sadd(set + ".srvs", localHostName + "#" + serverport);
					jedis.set(set + ".blk." + localHostName, "" + ssc.curBlock);
				}
				ssc.raf = new RandomAccessFile(ssc.newf, "rw");
				ssc.offset = ssc.newf.length();
				ssc.raf.seek(ssc.offset);
			} else {
				if (ssc.newf.length() + content.length > blocksize) {
					ssc.curBlock++;
					ssc.newf = new File(ssc.path + "b" + ssc.curBlock);
					if(ssc.raf != null)			//如果换了一个新块,则先把之前的关掉
						ssc.raf.close();
					ssc.raf = new RandomAccessFile(ssc.newf, "rw");
					jedis.incr(set + ".blk." + localHostName);			//当前可写的块号加一
					ssc.offset = 0;
				}
			}
			ssc.raf.write(content, coff, clen);

			// 构造返回值
			rVal.append("1#"); // type
			rVal.append(set);
			rVal.append("#");
			rVal.append(localHostName); // node name
			rVal.append("#");
			rVal.append(serverport); // port #
			rVal.append("#");
			rVal.append(ssc.curBlock);
			rVal.append("#");
			rVal.append(ssc.offset);
			rVal.append("#");
			rVal.append(clen);

			ssc.offset += clen;
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return "#FAIL:" + e.getMessage();
		} catch (IOException e) {
			e.printStackTrace();
			return "#FAIL:" + e.getMessage();
		}

		String returnVal = rVal.toString();
		// 确保多个进程生成的字符串只有一个被记录下来并且被完整的记录下
		Pipeline pipeline = jedis.pipelined();
		pipeline.hincrBy(set, "r." + md5, 1);
		pipeline.hsetnx(set, md5, returnVal);
		List<Object> l = pipeline.syncAndReturnAll();

		if (l != null && l.size() == 2) {
			if((Long)l.get(1) == 1)
				return returnVal;
			else
				return null;
		} else {
			return "#FAIL:update STR to redis server failed.";
		}
	}
	
	/**
	 * 存储多个图片，通过反复调用storePhoto实现
	 * @param set
	 * @param md5
	 * @param content
	 * @return
	 */
	public String[] mstorePhoto(String[] set, String[] md5, byte[][] content, int[] coff, int[] clen) {
		if(set.length == md5.length && md5.length == content.length) {
			int length = set.length;
			String[] infos = new String[length];
			for(int i = 0; i < length; i++) {
				infos[i] = storePhoto(set[i],md5[i],content[i], coff[i], clen[i]);
			}
			return infos;
		} else {
			System.out.println("Array lengths in arguments mismatch.");
			return null;
		}
	}
	/**
	 *获得md5值所代表的图片的内容
	 * @param md5		与storePhoto中的参数md5相对应
	 * @return			该图片的内容,与storePhoto中的参数content对应
	 */
	public byte[] getPhoto(String set, String md5) {
		String info = null;
		
		// Step 1: check the local lookup cache
		info = (String) lookupCache.get(set + "." + md5);
		if (info == null) {
			info = jedis.hget(set, md5);
			
			if(info == null) {
				System.out.println("MM: md5" + md5 + " doesn't exist in set " + set + ".");
				return null;
			} else {
				// append this entry to lookup cache
				lookupCache.put(set + "." + md5, info);
			}
		}
		return searchPhoto(info);
	}
	/**
	 * 获得图片内容
	 * @param info		对应storePhoto的type#set#node#port#block＃offset＃length格式的返回值
	 * @return			图片内容content
	 */
	public byte[] searchPhoto(String info) {
		String[] infos = info.split("#");
		
		if (infos.length != 7) {
			System.out.println("Invalid INFO string: " + info);
			return null;
		}
		String path = infos[1] + "/b" + infos[4];
		RandomAccessFile readr = null;
		byte[] content = new byte[Integer.parseInt(infos[6])];
	
		try {
			//用哈希缓存打开的文件随机访问流
			if(readRafHash.containsKey(path)) {
				readr = readRafHash.get(path);
			} else {
				readr = new RandomAccessFile(destRoot + path, "r");
				readRafHash.put(path, readr);
			}
			readr.seek(Long.parseLong(infos[5]));
			readr.read(content);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return null;
		} catch (NumberFormatException e) {
			e.printStackTrace();
			return null;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
		return content;
	}
	
	public void delSet(String set) {
		delFile(new File(destRoot + set));
	}
	
	/**
	 * 删除文件.如果是一个文件,直接删除,如果是文件夹,递归删除子文件夹和文件
	 * @param f
	 */
	private void delFile(File f) {
		if(!f.exists())
			return;
		if(f.isFile())
			f.delete();
		else {
			for (File a : f.listFiles())
				if (a.isFile())
					a.delete();
				else
					delFile(a);
			f.delete();
		}
	}
	
	//关闭jedis连接,关闭文件访问流
	public void close() {
		try {
			for (Map.Entry<String, StoreSetContext> entry : writeContextHash.entrySet()) {
				if (entry.getValue().raf != null)
					entry.getValue().raf.close();
			}
			for (Map.Entry<String, RandomAccessFile> entry : readRafHash.entrySet()) {
				entry.getValue().close();
			}
			jedis.quit();
		} catch(IOException e){
			e.printStackTrace();
		}
	}
	
}
