## Setup utilities for rosclj - based on rosjava setup

set(CATKIN_GLOBAL_MAVEN_DESTINATION ${CATKIN_GLOBAL_SHARE_DESTINATION}/maven CACHE PATH "path to which maven artifacts are deployed in your workspace")
set(CATKIN_GLOBAL_GRADLE_DESTINATION ${CATKIN_GLOBAL_SHARE_DESTINATION}/gradle CACHE PATH "path to which gradle configuration and artifacts are deployed in your workspace")

# Scans down directories till it finds the lein wrapper.
# It sets the following variables
# - ${PROJECT_NAME}_lein_BINARY
macro(find_lein)
    find_file(${PROJECT_NAME}_lein_BINARY lein
          PATHS 
          ${CMAKE_CURRENT_SOURCE_DIR}
          ${CMAKE_CURRENT_SOURCE_DIR}/..
          ${CMAKE_CURRENT_SOURCE_DIR}/../..
          NO_DEFAULT_PATH
          )
     if(NOT ${PROJECT_NAME}_lein_BINARY)
         message(FATAL_ERROR "Could not find the lein script in this directory or below.")
     endif()
endmacro()

# These are used to seed the environment variables if the workspace is
# containing rosjava_build_tools to be built as well. In this situtation
# CATKIN_ENV won't have any configuration, so we need some incoming here.
# Note that we check for the variable existence as well so we don't
# override a user setting.
macro(catkin_rosclj_env_setup)
  set(ROS_MAVEN_DEPLOYMENT_REPOSITORY $ENV{ROS_MAVEN_DEPLOYMENT_REPOSITORY})
  set(ROS_MAVEN_REPOSITORY $ENV{ROS_MAVEN_REPOSITORY})
  if(NOT ROS_MAVEN_DEPLOYMENT_REPOSITORY)
    set(ROSJAVA_ENV "ROS_MAVEN_DEPLOYMENT_REPOSITORY=${CATKIN_DEVEL_PREFIX}/${CATKIN_GLOBAL_MAVEN_DESTINATION}")
  else()
    set(ROSJAVA_ENV "ROS_MAVEN_DEPLOYMENT_REPOSITORY=${ROS_MAVEN_DEPLOYMENT_REPOSITORY}")
  endif()
  if(NOT ROS_MAVEN_REPOSITORY)
    list(APPEND ROSJAVA_ENV "ROS_MAVEN_REPOSITORY=https://github.com/rosjava/rosjava_mvn_repo/raw/master")
  else()
    set(ROSJAVA_ENV "ROS_MAVEN_REPOSITORY=${ROS_MAVEN_REPOSITORY}")
  endif()
  # The build farm won't let you access /root/.gradle, so redirect it somewhere practical here.
  if(DEFINED CATKIN_BUILD_BINARY_PACKAGE)
    list(APPEND ROSJAVA_ENV "GRADLE_USER_HOME=${CATKIN_DEVEL_PREFIX}/${CATKIN_GLOBAL_GRADLE_DESTINATION}")
  endif()
endmacro()

macro(catkin_rosclj_setup)
  catkin_rosclj_env_setup()
  find_lein()
  if( ${ARGC} EQUAL 0 )
    return() # Nothing to do (typically no subprojects created yet)
  else()
    set(lein_tasks ${ARGV})
  endif()
  set(lein_options "")
  ###################################
  # Execution
  ###################################
  add_custom_target(lein-${PROJECT_NAME} ALL
    COMMAND ${ROSJAVA_ENV} ${CATKIN_ENV} ${${PROJECT_NAME}_lein_BINARY} ${lein_options} ${lein_tasks}
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    VERBATIM
    COMMENT "Running Leiningen tasks for ${PROJECT_NAME}"
    )
  ###################################
  # Target Management
  ###################################
  catkin_package_xml()
  foreach(depends ${${PROJECT_NAME}_BUILD_DEPENDS})
    if(TARGET lein-${depends})
      #message(STATUS "Adding dependency.....gradle-${PROJECT_NAME} <- gradle-${depends}")
      add_dependencies(lein-${PROJECT_NAME} lein-${depends})
    endif()
    if(TARGET ${depends}_generate_messages)
      #message(STATUS "Adding dependency.....gradle-${PROJECT_NAME} <- ${depends}_generate_messages")
      add_dependencies(lein-${PROJECT_NAME} ${depends}_generate_messages)
    endif()
  endforeach()
  if(NOT TARGET lein-clean)
    add_custom_target(lein-clean)
  endif()
  add_custom_target(lein-clean-${PROJECT_NAME}
    COMMAND ${CATKIN_ENV} ${${PROJECT_NAME}_lein_BINARY} clean
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
    COMMENT "Cleaning Leiningen project for ${PROJECT_NAME}"
    )
  add_dependencies(lein-clean lein-clean-${PROJECT_NAME})
endmacro()
