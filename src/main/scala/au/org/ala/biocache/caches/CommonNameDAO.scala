package au.org.ala.biocache.caches

import au.org.ala.biocache.Config
import org.slf4j.LoggerFactory

/**
 * A cache DAO of common name lookups
 */
object CommonNameDAO {

  val logger = LoggerFactory.getLogger("CommonNameDAO")
  private val lru = new org.apache.commons.collections.map.LRUMap(10000)
  private val lock : AnyRef = new Object()
  private val nameIndex = Config.nameIndex

  /**
   * Uses a LRU cache of common name lookups.
   */
  def getByGuid(guid:String) : Option[String] = {

    if(guid == null){
      return None
    }

    val cachedObject = lock.synchronized { lru.get(guid) }

    if(cachedObject != null){
      cachedObject.asInstanceOf[Option[String]]
    } else {
      //lookup a name
      val commonName = Config.nameIndex.getCommonNameForLSID(guid)
      val result = if(commonName != null){
        Some(commonName)
      } else {
        None
      }
      lock.synchronized { lru.put(guid, result) }
      result
    }
  }
}
